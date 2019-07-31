;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns cook.scheduler.scheduler
  (:require [chime :refer [chime-at chime-ch]]
            [clj-time.coerce :as tc]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cook.compute-cluster :as cc]
            [cook.config :as config]
            [cook.datomic :as datomic]
            [cook.plugins.completion :as completion]
            [cook.plugins.definitions :as plugins]
            [cook.plugins.launch :as launch-plugin]
            [cook.scheduler.constraints :as constraints]
            [cook.scheduler.data-locality :as dl]
            [cook.scheduler.dru :as dru]
            [cook.scheduler.fenzo-utils :as fenzo]
            [cook.group :as group]
            [cook.pool :as pool]
            [cook.quota :as quota]
            [cook.mesos.reason :as reason]
            [cook.scheduler.share :as share]
            [cook.mesos.task :as task]
            [cook.tools :as util]
            [cook.rate-limit :as ratelimit]
            [cook.task]
            [datomic.api :as d :refer (q)]
            [mesomatic.scheduler :as mesos]
            [metatransaction.core :refer (db)]
            [metrics.counters :as counters]
            [metrics.gauges :as gauges]
            [metrics.histograms :as histograms]
            [metrics.meters :as meters]
            [metrics.timers :as timers]
            [plumbing.core :as pc])
  (import [com.netflix.fenzo TaskAssignmentResult TaskRequest TaskScheduler TaskScheduler$Builder
                             VirtualMachineLease VirtualMachineLease$Range
                             VirtualMachineCurrentState]
          [com.netflix.fenzo.functions Action1 Func1]))

(defn now
  []
  (tc/to-date (time/now)))

(defn offer-resource-values
  [offer resource-name value-type]
  (->> offer :resources (filter #(= (:name %) resource-name)) (map value-type)))

(defn offer-resource-scalar
  [offer resource-name]
  (reduce + 0.0 (offer-resource-values offer resource-name :scalar)))

(defn offer-resource-ranges
  [offer resource-name]
  (reduce into [] (offer-resource-values offer resource-name :ranges)))

(defn get-offer-attr-map
  "Gets all the attributes from an offer and puts them in a simple, less structured map of the form
   name->value"
  [offer]
  (let [mesos-attributes (->> offer
                              :attributes
                              (map #(vector (:name %) (case (:type %)
                                                        :value-scalar (:scalar %)
                                                        :value-ranges (:ranges %)
                                                        :value-set (:set %)
                                                        :value-text (:text %)
                                                        ; Default
                                                        (:value %))))
                              (into {}))
        cook-attributes {"HOSTNAME" (:hostname offer)
                         "COOK_GPU?" (-> offer
                                         (offer-resource-scalar "gpus")
                                         (or 0.0)
                                         pos?)}]
    (merge mesos-attributes cook-attributes)))

(timers/deftimer [cook-mesos scheduler handle-status-update-duration])
(timers/deftimer [cook-mesos scheduler handle-framework-message-duration])
(meters/defmeter [cook-mesos scheduler handle-framework-message-rate])

(timers/deftimer [cook-mesos scheduler generate-user-usage-map-duration])

(defn metric-title
  [metric-name pool]
  ["cook-mesos" "scheduler" metric-name (str "pool-" pool)])

(defn completion-rate-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "tasks-succeeded"
                      :failed "tasks-failed"
                      :completed "tasks-completed")]
    (meters/meter (metric-title metric-name pool))))

(defn completion-mem-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "tasks-succeeded-mem"
                      :failed "tasks-failed-mem"
                      :completed "tasks-completed-mem")]
    (meters/meter (metric-title metric-name pool))))

(defn completion-cpus-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "tasks-succeeded-cpus"
                      :failed "tasks-failed-cpus"
                      :completed "tasks-completed-cpus")]
    (meters/meter (metric-title metric-name pool))))

(defn completion-run-times-histogram
  [status pool]
  (let [metric-name (case status
                      :succeeded "hist-task-succeed-times"
                      :failed "hist-task-fail-times"
                      :completed "hist-task-complete-times")]
    (histograms/histogram (metric-title metric-name pool))))

(defn completion-run-times-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "task-succeed-times"
                      :failed "task-fail-times"
                      :completed "task-complete-times")]
    (meters/meter (metric-title metric-name pool))))

(defn handle-throughput-metrics [job-resources run-time status pool]
  (let [completion-rate (completion-rate-meter status pool)
        completion-mem (completion-mem-meter status pool)
        completion-cpus (completion-cpus-meter status pool)
        completion-hist-run-times (completion-run-times-histogram status pool)
        completion-MA-run-times (completion-run-times-meter status pool)]
    (meters/mark! completion-rate)
    (meters/mark!
      completion-mem
      (:mem job-resources))
    (meters/mark!
      completion-cpus
      (:cpus job-resources))
    (histograms/update!
      completion-hist-run-times
      run-time)
    (meters/mark!
      completion-MA-run-times
      run-time)))

(defn interpret-task-status
  "Converts the status packet from Mesomatic into a more friendly data structure"
  [s]
  {:task-id (-> s :task-id :value)
   :reason (:reason s)
   :task-state (:state s)
   :progress (try
               (when (:data s)
                 (:percent (edn/read-string (String. (.toByteArray (:data s))))))
               (catch Exception e
                 (try (log/debug e (str "Error parsing mesos status data to edn."
                                        "Is it in the format we expect?"
                                        "String representation: "
                                        (String. (.toByteArray (:data s)))))
                      (catch Exception e
                        (log/debug e "Error reading a string from mesos status data. Is it in the format we expect?")))))})

(defn update-reason-metrics!
  "Updates histograms and counters for run time, cpu time, and memory time,
  where the histograms have the failure reason in the title"
  [db mesos-reason instance-runtime {:keys [cpus mem]}]
  (let [reason (->> mesos-reason
                    (reason/mesos-reason->cook-reason-entity-id db)
                    (d/entity db)
                    :reason/name
                    name)
        update-metrics! (fn update-metrics! [s v]
                          (histograms/update!
                            (histograms/histogram
                              ["cook-mesos" "scheduler" "hist-task-fail" reason s])
                            v)
                          (counters/inc!
                            (counters/counter
                              ["cook-mesos" "scheduler" "hist-task-fail" reason s "total"])
                            v))
        instance-runtime-seconds (/ instance-runtime 1000)
        mem-gb (/ mem 1024)]
    (update-metrics! "times" instance-runtime-seconds)
    (update-metrics! "cpu-times" (* instance-runtime-seconds cpus))
    (update-metrics! "mem-times" (* instance-runtime-seconds mem-gb))))

(defn write-status-to-datomic
  "Takes a status update from mesos."
  [conn pool->fenzo status]
  (log/info "Mesos status is:" status)
  (timers/time!
    handle-status-update-duration
    (try (let [db (db conn)
               {:keys [task-id reason task-state progress]} (interpret-task-status status)
               _ (when-not task-id
                   (throw (ex-info "task-id is nil. Something unexpected has happened."
                                   {:status status
                                    :task-id task-id
                                    :reason reason
                                    :task-state task-state
                                    :progress progress})))
               [job instance prior-instance-status] (first (q '[:find ?j ?i ?status
                                                                :in $ ?task-id
                                                                :where
                                                                [?i :instance/task-id ?task-id]
                                                                [?i :instance/status ?s]
                                                                [?s :db/ident ?status]
                                                                [?j :job/instance ?i]]
                                                              db task-id))
               job-ent (d/entity db job)
               instance-ent (d/entity db instance)
               previous-reason (reason/instance-entity->reason-entity db instance-ent)
               instance-status (condp contains? task-state
                                 #{:task-staging} :instance.status/unknown
                                 #{:task-starting
                                   :task-running} :instance.status/running
                                 #{:task-finished} :instance.status/success
                                 #{:task-failed
                                   :task-killed
                                   :task-lost
                                   :task-error} :instance.status/failed)
               prior-job-state (:job/state (d/entity db job))
               current-time (now)
               instance-runtime (- (.getTime current-time) ; Used for reporting
                                   (.getTime (or (:instance/start-time instance-ent) current-time)))
               job-resources (util/job-ent->resources job-ent)
               pool-name (util/job->pool-name job-ent)
               ^TaskScheduler fenzo (get pool->fenzo pool-name)]
           (when (#{:instance.status/success :instance.status/failed} instance-status)
             (log/debug "Unassigning task" task-id "from" (:instance/hostname instance-ent))
             (try
               (locking fenzo
                 (.. fenzo
                     (getTaskUnAssigner)
                     (call task-id (:instance/hostname instance-ent))))
               (catch Exception e
                 (log/error e "Failed to unassign task" task-id "from" (:instance/hostname instance-ent)))))
           (when (= instance-status :instance.status/success)
             (handle-throughput-metrics job-resources instance-runtime :succeeded pool-name)
             (handle-throughput-metrics job-resources instance-runtime :completed pool-name))
           (when (= instance-status :instance.status/failed)
             (handle-throughput-metrics job-resources instance-runtime :failed pool-name)
             (handle-throughput-metrics job-resources instance-runtime :completed pool-name)
             (when-not previous-reason
               (update-reason-metrics! db reason instance-runtime job-resources)))
           (when-not (nil? instance)
             ;; (println "update:" task-id task-state job instance instance-status prior-job-state)
             (log/debug "Transacting updated state for instance" instance "to status" instance-status)
             ;; The database can become inconsistent if we make multiple calls to :instance/update-state in a single
             ;; transaction; see the comment in the definition of :instance/update-state for more details
             (let [transaction-chan (datomic/transact-with-retries
                                     conn
                                     (reduce
                                      into
                                      [[:instance/update-state instance instance-status (or (:db/id previous-reason)
                                                                                            (reason/mesos-reason->cook-reason-entity-id db reason)
                                                                                            [:reason.name :unknown])]] ; Warning: Default is not mea-culpa
                                      [(when (and (#{:instance.status/failed} instance-status) (not previous-reason) reason)
                                         [[:db/add instance :instance/reason (reason/mesos-reason->cook-reason-entity-id db reason)]])
                                       (when (and (#{:instance.status/success
                                                     :instance.status/failed} instance-status)
                                                  (nil? (:instance/end-time instance-ent)))
                                         [[:db/add instance :instance/end-time (now)]])
                                       (when (and (#{:task-starting :task-running} task-state)
                                                  (nil? (:instance/mesos-start-time instance-ent)))
                                         [[:db/add instance :instance/mesos-start-time (now)]])
                                       (when progress
                                         [[:db/add instance :instance/progress progress]])]))]
               (async/go
                 ; Wait for the transcation to complete before running the plugin
                 (let [chan-result (async/<! transaction-chan)]
                   (when (#{:instance.status/success :instance.status/failed} instance-status)
                     (let [db (d/db conn)
                           updated-job (d/entity db job)
                           updated-instance (d/entity db instance)]
                       (try
                         (plugins/on-instance-completion completion/plugin updated-job updated-instance)
                         (catch Exception e
                           (log/error e "Error while running instance completion plugin.")))))
                   chan-result)))))
         (catch Exception e
           (log/error e "Mesos scheduler status update error")))))

(defn- task-id->instance-id
  "Retrieves the instance-id given a task-id"
  [db task-id]
  (-> (d/entity db [:instance/task-id task-id])
      :db/id))

(defn handle-framework-message
  "Processes a framework message from Mesos."
  [conn {:keys [handle-exit-code handle-progress-message]}
   {:strs [exit-code progress-message progress-percent progress-sequence task-id] :as message}]
  (log/info "Received framework message:" {:task-id task-id, :message message})
  (timers/time!
    handle-framework-message-duration
    (try
      (when (str/blank? task-id)
        (throw (ex-info "task-id is empty in framework message" {:message message})))
      (let [instance-id (task-id->instance-id (db conn) task-id)]
        (if (nil? instance-id)
          (throw (ex-info "No instance found!" {:task-id task-id}))
          (do
            (when (or progress-message progress-percent)
              (log/debug "Updating instance" instance-id "progress to" progress-percent progress-message)
              (handle-progress-message {:instance-id instance-id
                                        :progress-message progress-message
                                        :progress-percent progress-percent
                                        :progress-sequence progress-sequence}))
            (when exit-code
              (log/info "Updating instance" instance-id "exit-code to" exit-code)
              (handle-exit-code task-id exit-code)))))
      (catch Exception e
        (log/error e "Mesos scheduler framework message error")))))

(timers/deftimer [cook-mesos scheduler tx-report-queue-processing-duration])
(meters/defmeter [cook-mesos scheduler tx-report-queue-datoms])
(meters/defmeter [cook-mesos scheduler tx-report-queue-update-job-state])
(meters/defmeter [cook-mesos scheduler tx-report-queue-job-complete])
(meters/defmeter [cook-mesos scheduler tx-report-queue-tasks-killed])

(defn monitor-tx-report-queue
  "Takes an async channel that will have tx report queue elements on it"
  [tx-report-chan conn]
  (log/info "Starting tx-report-queue")
  (let [kill-chan (async/chan)
        query-db (d/db conn)
        query-basis (d/basis-t query-db)
        tasks-to-kill (q '[:find ?i
                           :in $ [?status ...]
                           :where
                           [?i :instance/status ?status]
                           [?job :job/instance ?i]
                           [?job :job/state :job.state/completed]]
                         query-db [:instance.status/unknown :instance.status/running])]
    (doseq [[task-entity-id] tasks-to-kill]
      (let [task-id (cook.task/task-entity-id->task-id query-db task-entity-id)
            compute-cluster-name (cook.task/task-entity-id->compute-cluster-name query-db task-entity-id)]
        (if-let [compute-cluster (cc/compute-cluster-name->ComputeCluster compute-cluster-name)]
          (try
            (log/info "Attempting to kill task" task-id "in" compute-cluster-name "from already completed job")
            (meters/mark! tx-report-queue-tasks-killed)
            (cc/kill-task compute-cluster task-id)
            (catch Exception e
              (log/error e (str "Failed to kill task" task-id))))
          (log/warn "Unable to kill task" task-id "with unknown cluster" compute-cluster-name))))
    (async/go
      (loop []
        (async/alt!
          tx-report-chan ([tx-report]
                           (async/go
                             (timers/start-stop-time! ; Use this in go blocks, time! doesn't play nice
                               tx-report-queue-processing-duration
                               (let [{:keys [tx-data db-after]} tx-report]
                                 (when (< query-basis (d/basis-t db-after))
                                   (let [db (db conn)]
                                     (meters/mark! tx-report-queue-datoms (count tx-data))
                                     ;; Monitoring whether a job is completed.
                                     (doseq [{:keys [e a v]} tx-data]
                                       (try
                                         (when (and (= a (d/entid db :job/state))
                                                    (= v (d/entid db :job.state/completed)))
                                           (meters/mark! tx-report-queue-job-complete)
                                           (doseq [[task-entity-id]
                                                   (q '[:find ?i
                                                        :in $ ?job [?status ...]
                                                        :where
                                                        [?job :job/instance ?i]
                                                        [?i :instance/status ?status]]
                                                      db e [:instance.status/unknown
                                                            :instance.status/running])]
                                             (let [task-id (cook.task/task-entity-id->task-id db task-entity-id)
                                                   compute-cluster-name (cook.task/task-entity-id->compute-cluster-name db task-entity-id)]
                                               (if-let [compute-cluster (cc/compute-cluster-name->ComputeCluster compute-cluster-name)]
                                                 (do (log/info "Attempting to kill task" task-id "in" compute-cluster-name "due to job completion")
                                                     (meters/mark! tx-report-queue-tasks-killed)
                                                     (cc/kill-task compute-cluster task-id))
                                                 (log/error "Couldn't kill task" task-id "due to no Mesos driver for compute cluster" compute-cluster-name "!")))))
                                         (catch Exception e
                                           (log/error e "Unexpected exception on tx report queue processor")))))))))
                           (recur))
          kill-chan ([_] nil))))
    #(async/close! kill-chan)))

;;; ===========================================================================
;;; API for matcher
;;; ===========================================================================

(defrecord VirtualMachineLeaseAdapter [offer time]
  VirtualMachineLease
  (cpuCores [_] (or (offer-resource-scalar offer "cpus") 0.0))
  (diskMB [_] (or (offer-resource-scalar offer "disk") 0.0))
  (getScalarValue [_ name] (or (double (offer-resource-scalar offer name)) 0.0))
  (getScalarValues [_]
    (reduce (fn [result resource]
              (if-let [value (:scalar resource)]
                ;; Do not remove the following fnil--either arg to + can be nil!
                (update-in result [(:name resource)] (fnil + 0.0 0.0) value)
                result))
            {}
            (:resources offer)))
  ; Some Fenzo plugins (which are included with fenzo, such as host attribute constraints) expect the "HOSTNAME"
  ; attribute to contain the hostname of this virtual machine.
  (getAttributeMap [_] (get-offer-attr-map offer))
  (getId [_] (-> offer :id :value))
  (getOffer [_] (throw (UnsupportedOperationException.)))
  (getOfferedTime [_] time)
  (getVMID [_] (-> offer :slave-id :value))
  (hostname [_] (:hostname offer))
  (memoryMB [_] (or (offer-resource-scalar offer "mem") 0.0))
  (networkMbps [_] 0.0)
  (portRanges [_] (mapv (fn [{:keys [begin end]}]
                          (VirtualMachineLease$Range. begin end))
                        (offer-resource-ranges offer "ports"))))


(defrecord TaskRequestAdapter [job resources task-id assigned-resources guuid->considerable-cotask-ids constraints needs-gpus? scalar-requests]
  TaskRequest
  (getCPUs [_] (:cpus resources))
  (getDisk [_] 0.0)
  (getHardConstraints [_] constraints)
  (getId [_] task-id)
  (getScalarRequests [_] scalar-requests)
  (getAssignedResources [_] @assigned-resources)
  (setAssignedResources [_ v] (reset! assigned-resources v))
  (getCustomNamedResources [_] {})
  (getMemory [_] (:mem resources))
  (getNetworkMbps [_] 0.0)
  (getPorts [_] (:ports resources))
  (getSoftConstraints [_] [])
  (taskGroupName [_] (str (:job/uuid job))))

(defn make-task-request
  "Helper to create a TaskRequest using TaskRequestAdapter. TaskRequestAdapter implements Fenzo's TaskRequest interface
   given a job, its resources, its task-id and a function assigned-cotask-getter. assigned-cotask-getter should be a
   function that takes a group uuid and returns a set of task-ids, which correspond to the tasks that will be assigned
   during the same Fenzo scheduling cycle as the newly created TaskRequest."
  [db job & {:keys [resources task-id assigned-resources guuid->considerable-cotask-ids reserved-hosts running-cotask-cache]
             :or {resources (util/job-ent->resources job)
                  task-id (str (d/squuid))
                  assigned-resources (atom nil)
                  guuid->considerable-cotask-ids (constantly #{})
                  running-cotask-cache (atom (cache/fifo-cache-factory {} :threshold 1))
                  reserved-hosts #{}}}]
  (let [constraints (-> (constraints/make-fenzo-job-constraints job)
                        (conj (constraints/build-rebalancer-reservation-constraint reserved-hosts))
                        (into
                          (remove nil?
                                  (mapv (fn make-group-constraints [group]
                                          (constraints/make-fenzo-group-constraint
                                            db group #(guuid->considerable-cotask-ids (:group/uuid group)) running-cotask-cache))
                                        (:group/_job job)))))
        needs-gpus? (constraints/job-needs-gpus? job)
        scalar-requests (reduce (fn [result resource]
                                  (if-let [value (:resource/amount resource)]
                                    (assoc result (name (:resource/type resource)) value)
                                    result))
                                {}
                                (:job/resource job))]
    (->TaskRequestAdapter job resources task-id assigned-resources guuid->considerable-cotask-ids constraints needs-gpus? scalar-requests)))

(defn match-offer-to-schedule
  "Given an offer and a schedule, computes all the tasks should be launched as a result.

   A schedule is just a sorted list of tasks, and we're going to greedily assign them to
   the offer.

   Returns {:matches (list of tasks that got matched to the offer)
            :failures (list of unmatched tasks, and why they weren't matched)}"
  [db ^TaskScheduler fenzo considerable offers rebalancer-reservation-atom]
  (log/info "Matching" (count offers) "offers to" (count considerable) "jobs with fenzo")
  (log/debug "tasks to scheduleOnce" considerable)
  (dl/update-cost-staleness-metric considerable)
  (let [t (System/currentTimeMillis)
        _ (log/debug "tasks to scheduleOnce" considerable)
        leases (mapv #(->VirtualMachineLeaseAdapter % t) offers)
        considerable->task-id (plumbing.core/map-from-keys (fn [_] (str (d/squuid))) considerable)
        guuid->considerable-cotask-ids (util/make-guuid->considerable-cotask-ids considerable->task-id)
        running-cotask-cache (atom (cache/fifo-cache-factory {} :threshold (max 1 (count considerable))))
        job-uuid->reserved-host (or (:job-uuid->reserved-host @rebalancer-reservation-atom) {})
        reserved-hosts (into (hash-set) (vals job-uuid->reserved-host))
        ; Important that requests maintains the same order as considerable
        requests (mapv (fn [job]
                         (make-task-request db job
                                            :guuid->considerable-cotask-ids guuid->considerable-cotask-ids
                                            :reserved-hosts (disj reserved-hosts (job-uuid->reserved-host (:job/uuid job)))
                                            :running-cotask-cache running-cotask-cache
                                            :task-id (considerable->task-id job)))
                       considerable)
        ;; Need to lock on fenzo when accessing scheduleOnce because scheduleOnce and
        ;; task assigner can not be called at the same time.
        ;; task assigner may be called when reconciling
        result (locking fenzo
                 (.scheduleOnce fenzo requests leases))
        failure-results (.. result getFailures values)
        assignments (.. result getResultMap values)]
    (doall (map (fn [^VirtualMachineLease lease]
                  (when (-> lease :offer :reject-after-match-attempt)
                    (log/info "Retracting lease" (-> lease :offer :id))
                    (locking fenzo
                      (.expireLease fenzo (.getId lease)))))
                leases))

    (log/debug "Found this assignment:" result)

    {:matches (mapv (fn [assignment]
                      {:leases (.getLeasesUsed assignment)
                       :tasks (.getTasksAssigned assignment)
                       :hostname (.getHostname assignment)})
                    assignments)
     :failures failure-results}))

(meters/defmeter [cook-mesos scheduler scheduler-offer-declined])

(defn decline-offers
  "declines a collection of offer ids"
  [compute-cluster offer-ids]
  (log/debug "Declining offers:" offer-ids)
  (meters/mark! scheduler-offer-declined (count offer-ids))
  (cc/decline-offers compute-cluster offer-ids))

(histograms/defhistogram [cook-mesos scheduler number-tasks-matched])
(histograms/defhistogram [cook-mesos-scheduler number-offers-matched])
(meters/defmeter [cook-mesos scheduler scheduler-offer-matched])
(meters/defmeter [cook-mesos scheduler handle-resource-offer!-errors])
(def front-of-job-queue-mem-atom (atom 0))
(def front-of-job-queue-cpus-atom (atom 0))
(gauges/defgauge [cook-mesos scheduler front-of-job-queue-mem] (fn [] @front-of-job-queue-mem-atom))
(gauges/defgauge [cook-mesos scheduler front-of-job-queue-cpus] (fn [] @front-of-job-queue-cpus-atom))

(defn generate-user-usage-map
  "Returns a mapping from user to usage stats"
  [unfiltered-db pool-name]
  (timers/time!
    generate-user-usage-map-duration
    (->> (util/get-running-task-ents unfiltered-db)
         (map :job/_instance)
         (remove #(not= pool-name (util/job->pool-name %)))
         (group-by :job/user)
         (pc/map-vals (fn [jobs]
                        (->> jobs
                             (map util/job->usage)
                             (reduce (partial merge-with +))))))))

;Shared as we use this for unscheduled too.
(defonce pool->user->number-jobs (atom {}))

(defn pending-jobs->considerable-jobs
  "Limit the pending jobs to considerable jobs based on usage and quota.
   Further limit the considerable jobs to a maximum of num-considerable jobs."
  [db pending-jobs user->quota user->usage num-considerable pool-name]
  (log/debug "In" pool-name "pool, there are" (count pending-jobs) "pending jobs:" pending-jobs)
  (let [enforcing-job-launch-rate-limit? (ratelimit/enforce? ratelimit/job-launch-rate-limiter)
        user->number-jobs (atom {})
        user->rate-limit-count (atom {})
        user-within-launch-rate-limit?-fn
        (fn
          [{:keys [job/user]}]
          ; Account for each time we see a job for a user.
          (swap! user->number-jobs update user #(inc (or % 0)))
          (let [tokens (ratelimit/get-token-count! ratelimit/job-launch-rate-limiter user)
                number-jobs-for-user-so-far (@user->number-jobs user)
                is-rate-limited? (> number-jobs-for-user-so-far tokens)]
            (when is-rate-limited?
              (swap! user->rate-limit-count update user #(inc (or % 0))))
            (not (and is-rate-limited? enforcing-job-launch-rate-limit?))))
        considerable-jobs
        (->> pending-jobs
             (util/filter-based-on-quota user->quota user->usage)
             (filter (fn [job] (util/job-allowed-to-start? db job)))
             (filter user-within-launch-rate-limit?-fn)
             (filter launch-plugin/filter-job-launches)
             (take num-considerable)
             ; Force this to be taken eagerly so that the log line is accurate.
             (doall))]
    (swap! pool->user->number-jobs update pool-name (constantly @user->number-jobs))
    (log/info "Users whose job launches are rate-limited " @user->rate-limit-count)
    considerable-jobs))


(defn matches->job-uuids
  "Returns the matched job uuids."
  [matches pool-name]
  (let [jobs (->> matches
                  (mapcat #(-> % :tasks))
                  (map #(-> % .getRequest :job)))
        job-uuids (set (map :job/uuid jobs))]
    (log/debug "In" pool-name "pool, matched jobs:" (count job-uuids))
    (when (seq matches)
      (let [matched-normal-jobs-resource-requirements (util/sum-resources-of-jobs jobs)]
        (meters/mark! (meters/meter (metric-title "matched-tasks-cpus" pool-name))
                      (:cpus matched-normal-jobs-resource-requirements))
        (meters/mark! (meters/meter (metric-title "matched-tasks-mem" pool-name))
                      (:mem matched-normal-jobs-resource-requirements))))
    job-uuids))

(defn remove-matched-jobs-from-pending-jobs
  "Removes matched jobs from pool->pending-jobs."
  [pool-name->pending-jobs matched-job-uuids pool-name]
  (update-in pool-name->pending-jobs [pool-name]
             (fn [jobs]
               (remove #(contains? matched-job-uuids (:job/uuid %)) jobs))))

(defn- update-match-with-task-metadata-seq
  "Updates the match with an entry for the task metadata for all tasks. A 'match' is a set of jobs that we
  will want to all run on the same host."
  [{:keys [tasks leases] :as match} db mesos-run-as-user]
  (let [offers (mapv :offer leases)
        first-offer (-> offers first)
        compute-cluster (-> first-offer :compute-cluster)]
    (->> tasks
         ;; sort-by makes task-txns created in matches->task-txns deterministic
         (sort-by (comp :job/uuid :job #(.getRequest ^TaskAssignmentResult %)))
         (map (partial task/TaskAssignmentResult->task-metadata db mesos-run-as-user compute-cluster))
         (assoc match :task-metadata-seq))))

(defn- matches->task-txns
  "Converts matches to a task transactions."
  [matches]
  (for [{:keys [leases task-metadata-seq]} matches
        :let [offers (mapv :offer leases)
              first-offer (-> offers first)
              slave-id (-> first-offer :slave-id :value)]
        {:keys [executor hostname ports-assigned task-id task-request]} task-metadata-seq
        :let [job-ref [:job/uuid (get-in task-request [:job :job/uuid])]]]
    [[:job/allowed-to-start? job-ref]
     ;; NB we set any job with an instance in a non-terminal
     ;; state to running to prevent scheduling the same job
     ;; twice; see schema definition for state machine
     [:db/add job-ref :job/state :job.state/running]
     {:db/id (d/tempid :db.part/user)
      :job/_instance job-ref
      :instance/executor executor
      :instance/executor-id task-id ;; NB command executor uses the task-id as the executor-id
      :instance/hostname hostname
      :instance/ports ports-assigned
      :instance/preempted? false
      :instance/progress 0
      :instance/slave-id slave-id
      :instance/start-time (now)
      :instance/status :instance.status/unknown
      :instance/task-id task-id
      :instance/compute-cluster
      (-> first-offer
          :compute-cluster
          cc/db-id)}]))

(defn launch-matched-tasks!
  "Updates the state of matched tasks in the database and then launches them."
  [matches conn db fenzo mesos-run-as-user pool-name]
  (let [matches (map #(update-match-with-task-metadata-seq % db mesos-run-as-user) matches)
        task-txns (matches->task-txns matches)]
    (log/info "Writing tasks" task-txns)
    ;; Note that this transaction can fail if a job was scheduled
    ;; during a race. If that happens, then other jobs that should
    ;; be scheduled will not be eligible for rescheduling until
    ;; the pending-jobs atom is repopulated
    (timers/time!
      (timers/timer (metric-title "handle-resource-offer!-transact-task-duration" pool-name))
      (datomic/transact
        conn
        (reduce into [] task-txns)
        (fn [e]
          (log/warn e
                    "Transaction timed out, so these tasks might be present"
                    "in Datomic without actually having been launched in Mesos"
                    matches)
          (throw e))))
    (log/info "Launching" (count task-txns) "tasks")
    (ratelimit/spend! ratelimit/global-job-launch-rate-limiter ratelimit/global-job-launch-rate-limiter-key (count task-txns))
    (log/debug "Matched tasks" task-txns)
    ;; This launch-tasks MUST happen after the above transaction in
    ;; order to allow a transaction failure (due to failed preconditions)
    ;; to block the launch
    (let [num-offers-matched (->> matches
                                  (mapcat (comp :id :offer :leases))
                                  (distinct)
                                  (count))]
      (meters/mark! scheduler-offer-matched num-offers-matched)
      (histograms/update! number-offers-matched num-offers-matched))
    (meters/mark! (meters/meter (metric-title "matched-tasks" pool-name)) (count task-txns))
    (timers/time!
      (timers/timer (metric-title "handle-resource-offer!-mesos-submit-duration" pool-name))
      ;; Iterates over offers (each offer can match to multiple tasks)
      (doseq [{:keys [leases task-metadata-seq]} matches
              :let [all-offers (mapv :offer leases)]]
        (doseq [[compute-cluster offers] (group-by :compute-cluster all-offers)]
          (cc/launch-tasks compute-cluster offers task-metadata-seq)
          (log/info "Launching " (count offers) "offers for" (cc/compute-cluster-name compute-cluster) "compute cluster")
          (doseq [{:keys [hostname task-request] :as meta} task-metadata-seq]
            ; Iterate over the tasks we matched
          (let [user (get-in task-request [:job :job/user])]
            (ratelimit/spend! ratelimit/job-launch-rate-limiter user 1))
          (locking fenzo
            (.. fenzo
                (getTaskAssigner)
                (call task-request hostname)))))))))

(defn update-host-reservations!
  "Updates the rebalancer-reservation-atom with the result of the match cycle.
   - Releases reservations for jobs that were matched
   - Adds matched job uuids to the launched-job-uuids list"
  [rebalancer-reservation-atom matched-job-uuids]
  (swap! rebalancer-reservation-atom (fn [{:keys [job-uuid->reserved-host launched-job-uuids]}]
                                       {:job-uuid->reserved-host (apply dissoc job-uuid->reserved-host matched-job-uuids)
                                        :launched-job-uuids (into matched-job-uuids launched-job-uuids)})))

(defn handle-resource-offers!
  "Gets a list of offers from mesos. Decides what to do with them all--they should all
   be accepted or rejected at the end of the function."
  [conn ^TaskScheduler fenzo pool-name->pending-jobs-atom mesos-run-as-user
   user->usage user->quota num-considerable compute-cluster offers rebalancer-reservation-atom pool-name]
  (log/debug "In" pool-name "pool, invoked handle-resource-offers!")
  (let [offer-stash (atom nil)] ;; This is a way to ensure we never lose offers fenzo assigned if an error occurs in the middle of processing
    ;; TODO: It is possible to have an offer expire by mesos because we recycle it a bunch of times.
    ;; TODO: If there is an exception before offers are sent to fenzo (scheduleOnce) then the offers will be lost. This is fine with offer expiration, but not great.
    (timers/time!
      (timers/timer (metric-title "handle-resource-offer!-duration" pool-name))
      (try
        (let [db (db conn)
              pending-jobs (get @pool-name->pending-jobs-atom pool-name)
              considerable-jobs (timers/time!
                                  (timers/timer (metric-title "handle-resource-offer!-considerable-jobs-duration" pool-name))
                                  (pending-jobs->considerable-jobs
                                    db pending-jobs user->quota user->usage num-considerable pool-name))
              ; matches is a vector of maps of {:hostname .. :leases .. :tasks}
              {:keys [matches failures]} (timers/time!
                                           (timers/timer (metric-title "handle-resource-offer!-match-duration" pool-name))
                                           (match-offer-to-schedule db fenzo considerable-jobs offers rebalancer-reservation-atom))
              _ (log/debug "In" pool-name "pool, got matches:" matches)
              offers-scheduled (for [{:keys [leases]} matches
                                     lease leases]
                                 (:offer lease))
              matched-job-uuids (timers/time!
                                  (timers/timer (metric-title "handle-resource-offer!-match-job-uuids-duration" pool-name))
                                  (matches->job-uuids matches pool-name))
              first-considerable-job-resources (-> considerable-jobs first util/job-ent->resources)
              matched-considerable-jobs-head? (contains? matched-job-uuids (-> considerable-jobs first :job/uuid))]

          (fenzo/record-placement-failures! conn failures)

          (reset! offer-stash offers-scheduled)
          (reset! front-of-job-queue-mem-atom (or (:mem first-considerable-job-resources) 0))
          (reset! front-of-job-queue-cpus-atom (or (:cpus first-considerable-job-resources) 0))

          (cond
            ;; Possible innocuous reasons for no matches: no offers, or no pending jobs.
            ;; Even beyond that, if Fenzo fails to match ANYTHING, "penalizing" it in the form of giving
            ;; it fewer jobs to look at is unlikely to improve the situation.
            ;; "Penalization" should only be employed when Fenzo does successfully match,
            ;; but the matches don't align with Cook's priorities.
            (empty? matches) true
            :else
            (do
              (swap! pool-name->pending-jobs-atom remove-matched-jobs-from-pending-jobs matched-job-uuids pool-name)
              (log/debug "In" pool-name "pool, updated pool-name->pending-jobs-atom:" @pool-name->pending-jobs-atom)
              (launch-matched-tasks! matches conn db fenzo mesos-run-as-user pool-name)
              (update-host-reservations! rebalancer-reservation-atom matched-job-uuids)
              matched-considerable-jobs-head?)))
        (catch Throwable t
          (meters/mark! handle-resource-offer!-errors)
          (log/error t "In" pool-name "pool, error in match:" (ex-data t))
          (when-let [offers @offer-stash]
            (cc/restore-offers compute-cluster pool-name offers))
          ; if an error happened, it doesn't mean we need to penalize Fenzo
          true)))))

(defn view-incubating-offers
  [^TaskScheduler fenzo]
  (let [pending-offers (for [^VirtualMachineCurrentState state (locking fenzo (.getVmCurrentStates fenzo))
                             :let [lease (.getCurrAvailableResources state)]
                             :when lease]
                         {:hostname (.hostname lease)
                          :slave-id (.getVMID lease)
                          :resources (.getScalarValues lease)})]
    (log/debug "We have" (count pending-offers) "pending offers")
    pending-offers))

(def fenzo-num-considerable-atom (atom 0))
(gauges/defgauge [cook-mesos scheduler fenzo-num-considerable] (fn [] @fenzo-num-considerable-atom))
(counters/defcounter [cook-mesos scheduler iterations-at-fenzo-floor])
(meters/defmeter [cook-mesos scheduler fenzo-abandon-and-reset-meter])
(counters/defcounter [cook-mesos scheduler offer-chan-depth])

(defn make-offer-handler
  [conn fenzo pool-name->pending-jobs-atom agent-attributes-cache max-considerable scaleback
   floor-iterations-before-warn floor-iterations-before-reset trigger-chan rebalancer-reservation-atom
   mesos-run-as-user pool-name compute-cluster]
  (let [resources-atom (atom (view-incubating-offers fenzo))]
    (reset! fenzo-num-considerable-atom max-considerable)
    (util/chime-at-ch
      trigger-chan
      (fn match-jobs-event []
        (let [num-considerable @fenzo-num-considerable-atom
              next-considerable
              (try
                (let [
                      ;; There are implications to generating the user->usage here:
                      ;;  1. Currently cook has two oddities in state changes.
                      ;;  We plan to correct both of these but are important for the time being.
                      ;;    a. Cook doesn't mark as a job as running when it schedules a job.
                      ;;       While this is technically correct, it confuses some process.
                      ;;       For example, it will mean that the user->usage generated here
                      ;;       may not include jobs that have been scheduled but haven't started.
                      ;;       Since we do the filter for quota first, this is ok because those jobs
                      ;;       show up in the queue. However, it is important to know about
                      ;;    b. Cook doesn't update the job state when cook hears from mesos about the
                      ;;       state of an instance. Cook waits until it hears from datomic about the
                      ;;       instance state change to change the state of the job. This means that it
                      ;;       is possible to have large delays between when a instance changes status
                      ;;       and the job reflects that change
                      ;;  2. Once the above two items are addressed, user->usage should always correctly
                      ;;     reflect *Cook*'s understanding of the state of the world at this point.
                      ;;     When this happens, users should never exceed their quota
                      user->usage-future (future (generate-user-usage-map (d/db conn) pool-name))
                      ;; Try to clear the channel
                      offers (cc/pending-offers compute-cluster pool-name)
                      _ (doseq [offer offers
                                :let [slave-id (-> offer :slave-id :value)
                                      attrs (get-offer-attr-map offer)]]
                          ; Cache all used offers (offer-cache is a map of hostnames to most recent offer)
                          (swap! agent-attributes-cache (fn [c]
                                                          (if (cache/has? c slave-id)
                                                            (cache/hit c slave-id)
                                                            (cache/miss c slave-id attrs)))))
                      using-pools? (not (nil? (config/default-pool)))
                      user->quota (quota/create-user->quota-fn (d/db conn) (if using-pools? pool-name nil))
                      matched-head? (handle-resource-offers! conn fenzo pool-name->pending-jobs-atom
                                                             mesos-run-as-user @user->usage-future user->quota
                                                             num-considerable compute-cluster offers
                                                             rebalancer-reservation-atom pool-name)]
                  (when (seq offers)
                    (reset! resources-atom (view-incubating-offers fenzo)))
                  ;; This check ensures that, although we value Fenzo's optimizations,
                  ;; we also value Cook's sensibility of fairness when deciding which jobs
                  ;; to schedule.  If Fenzo produces a set of matches that doesn't include
                  ;; Cook's highest-priority job, on the next cycle, we give Fenzo it less
                  ;; freedom in the form of fewer jobs to consider.
                  (if matched-head?
                    max-considerable
                    (let [new-considerable (max 1 (long (* scaleback num-considerable)))] ;; With max=1000 and 1 iter/sec, this will take 88 seconds to reach 1
                      (log/info "Failed to match head, reducing number of considerable jobs" {:prev-considerable num-considerable
                                                                                              :new-considerable new-considerable
                                                                                              :pool pool-name})
                      new-considerable)
                    ))
                (catch Exception e
                  (log/error e "Offer handler encountered exception; continuing")
                  max-considerable))]

          (if (= next-considerable 1)
            (counters/inc! iterations-at-fenzo-floor)
            (counters/clear! iterations-at-fenzo-floor))

          (if (>= (counters/value iterations-at-fenzo-floor) floor-iterations-before-warn)
            (log/warn "Offer handler has been showing Fenzo only 1 job for "
                      (counters/value iterations-at-fenzo-floor) " iterations."))

          (reset! fenzo-num-considerable-atom
                  (if (>= (counters/value iterations-at-fenzo-floor) floor-iterations-before-reset)
                    (do
                      (log/error "FENZO CANNOT MATCH THE MOST IMPORTANT JOB."
                                 "Fenzo has seen only 1 job for " (counters/value iterations-at-fenzo-floor)
                                 "iterations, and still hasn't matched it.  Cook is now giving up and will "
                                 "now give Fenzo " max-considerable " jobs to look at.")
                      (meters/mark! fenzo-abandon-and-reset-meter)
                      max-considerable)
                    next-considerable))))
      {:error-handler (fn [ex] (log/error ex "Error occurred in match"))})
    resources-atom))

(defn reconcile-jobs
  "Ensure all jobs saw their final state change"
  [conn]
  (let [jobs (map first (q '[:find ?j
                             :in $ [?status ...]
                             :where
                             [?j :job/state ?status]]
                           (db conn) [:job.state/waiting
                                      :job.state/running]))]
    (doseq [js (partition-all 25 jobs)]
      (async/<!! (datomic/transact-with-retries conn
                                                (mapv (fn [j]
                                                        [:job/update-state j])
                                                      js))))))

;; TODO test that this fenzo recovery system actually works
(defn reconcile-tasks
  "Finds all non-completed tasks, and has Mesos let us know if any have changed."
  [db driver pool->fenzo]
  (let [running-tasks (q '[:find ?task-id ?status ?slave-id
                           :in $ [?status ...]
                           :where
                           [?i :instance/status ?status]
                           [?i :instance/task-id ?task-id]
                           [?i :instance/slave-id ?slave-id]]
                         db
                         [:instance.status/unknown
                          :instance.status/running])
        sched->mesos {:instance.status/unknown :task-staging
                      :instance.status/running :task-running}]
    (when (seq running-tasks)
      (log/info "Preparing to reconcile" (count running-tasks) "tasks")
      ;; TODO: When turning on periodic reconcilation, probably want to move this to startup
      (let [processed-tasks (->> (for [task running-tasks
                                       :let [[task-id] task
                                             task-ent (d/entity db [:instance/task-id task-id])
                                             hostname (:instance/hostname task-ent)]]
                                   (when-let [job (util/job-ent->map (:job/_instance task-ent))]
                                     (let [task-request (make-task-request db job :task-id task-id)
                                           ^TaskScheduler fenzo (-> job util/job->pool-name pool->fenzo)]
                                       ;; Need to lock on fenzo when accessing taskAssigner because taskAssigner and
                                       ;; scheduleOnce can not be called at the same time.
                                       (locking fenzo
                                         (.. fenzo
                                             (getTaskAssigner)
                                             (call task-request hostname)))
                                       task)))
                                 (remove nil?))]
        (when (not= (count running-tasks) (count processed-tasks))
          (log/error "Skipping reconciling" (- (count running-tasks) (count processed-tasks)) "tasks"))
        (doseq [ts (partition-all 50 processed-tasks)]
          (log/info "Reconciling" (count ts) "tasks, including task" (first ts))
          (try
            (mesos/reconcile-tasks driver (mapv (fn [[task-id status slave-id]]
                                                  {:task-id {:value task-id}
                                                   :state (sched->mesos status)
                                                   :slave-id {:value slave-id}})
                                                ts))
            (catch Exception e
              (log/error e "Reconciliation error")))))
      (log/info "Finished reconciling all tasks"))))

;; Unfortunately, clj-time.core/millis only accepts ints, not longs.
;; The Period class has a constructor that accepts "long milliseconds",
;; but since that isn't exposed through the clj-time API, we have to call it directly.
(defn- millis->period
  "Create a time period (duration) from a number of milliseconds."
  [millis]
  (org.joda.time.Period. (long millis)))

(defn get-lingering-tasks
  "Return a list of lingering tasks.

   A lingering task is a task that runs longer than timeout-hours."
  [db now max-timeout-hours default-timeout-hours]
  (let [jobs-with-max-runtime
        (q '[:find ?task-id ?start-time ?max-runtime
             :in $ ?default-runtime
             :where
             [(ground [:instance.status/unknown :instance.status/running]) [?status ...]]
             [?i :instance/status ?status]
             [?i :instance/task-id ?task-id]
             [?i :instance/start-time ?start-time]
             [?j :job/instance ?i]
             [(get-else $ ?j :job/max-runtime ?default-runtime) ?max-runtime]]
           db (-> default-timeout-hours time/hours time/in-millis))
        max-allowed-timeout-ms (-> max-timeout-hours time/hours time/in-millis)]
    (for [[task-id start-time max-runtime-ms] jobs-with-max-runtime
          :let [timeout-period (millis->period (min max-runtime-ms max-allowed-timeout-ms))
                timeout-boundary (time/plus (tc/from-date start-time) timeout-period)]
          :when (time/after? now timeout-boundary)]
      task-id)))

; TODO: Should get the compute-cluster from the task structure, and kill based on that.
(defn kill-lingering-tasks
  [now conn compute-cluster config]
  (let [{:keys [max-timeout-hours
                default-timeout-hours
                timeout-hours]} config
        db (d/db conn)
        ;; These defaults are for backwards compatibility
        max-timeout-hours (or max-timeout-hours timeout-hours)
        default-timeout-hours (or default-timeout-hours timeout-hours)
        lingering-tasks (get-lingering-tasks db now max-timeout-hours default-timeout-hours)]
    (when (seq lingering-tasks)
      (log/info "Starting to kill lingering jobs running more than their max-runtime."
                {:default-timeout-hours default-timeout-hours
                 :max-timeout-hours max-timeout-hours}
                "There are in total" (count lingering-tasks) "lingering tasks.")
      (doseq [task-id lingering-tasks]
        (log/info "Killing lingering task" task-id)
        ;; Note that we probably should update db to mark a task failed as well.
        ;; However in the case that we fail to kill a particular task in Mesos,
        ;; we could lose the chances to kill this task again.
        (cc/kill-task compute-cluster task-id)
        ;; BUG - the following transaction races with the update that is triggered
        ;; when the task is actually killed and sends its exit status code.
        ;; See issue #515 on GitHub.
        @(d/transact
           conn
           [[:instance/update-state [:instance/task-id task-id] :instance.status/failed [:reason/name :max-runtime-exceeded]]
            [:db/add [:instance/task-id task-id] :instance/reason [:reason/name :max-runtime-exceeded]]])))))

; Should not use driver as an argument.
(defn lingering-task-killer
  "Periodically kill lingering tasks.

   The config is a map with optional keys where
   :timout-hours specifies the timeout hours for lingering tasks"
  [conn compute-cluster config trigger-chan]
  (let [config (merge {:timeout-hours (* 2 24)}
                      config)]
    (util/chime-at-ch trigger-chan
                      (fn kill-linger-task-event []
                        (kill-lingering-tasks (time/now) conn compute-cluster config))
                      {:error-handler (fn [e]
                                        (log/error e "Failed to reap timeout tasks!"))})))

(defn handle-stragglers
  "Searches for running jobs in groups and runs the associated straggler handler"
  [conn kill-task-fn]
  (let [running-task-ents (util/get-running-task-ents (d/db conn))
        running-job-ents (map :job/_instance running-task-ents)
        groups (distinct (mapcat :group/_job running-job-ents))]
    (doseq [group groups]
      (log/debug "Checking group " (d/touch group) " for stragglers")

      (let [straggler-task-ents (group/find-stragglers group)]
        (log/debug "Group " group " had stragglers: " straggler-task-ents)

        (doseq [{task-ent-id :db/id :as task-ent} straggler-task-ents]
          (log/info "Killing " task-ent " of group " (:group/uuid group) " because it is a straggler")
          ;; Mark as killed first so that if we fail after this it is still marked failed
          @(d/transact
             conn
             [[:instance/update-state task-ent-id :instance.status/failed [:reason/name :straggler]]
              [:db/add task-ent-id :instance/reason [:reason/name :straggler]]])
          (kill-task-fn task-ent))))))

(defn straggler-handler
  "Periodically checks for running jobs that are in groups and runs the associated
   straggler handler."
  [conn compute-cluster trigger-chan]
  (util/chime-at-ch trigger-chan
                    (fn straggler-handler-event []
                      (handle-stragglers conn #(cc/kill-task compute-cluster (:instance/task-id %))))
                    {:error-handler (fn [e]
                                      (log/error e "Failed to handle stragglers"))}))

(defn killable-cancelled-tasks
  [db]
  (->> (q '[:find ?i
            :in $ [?status ...]
            :where
            [?i :instance/cancelled true]
            [?i :instance/status ?status]]
          db [:instance.status/running :instance.status/unknown])
       (map (fn [[x]] (d/entity db x)))))

(timers/deftimer [cook-mesos scheduler killing-cancelled-tasks-duration])

(defn cancelled-task-killer
  "Every trigger, kill tasks that have been cancelled (e.g. via the API)."
  [conn compute-cluster trigger-chan]
  (util/chime-at-ch
    trigger-chan
    (fn cancelled-task-killer-event []
      (timers/time!
        killing-cancelled-tasks-duration
        (doseq [task (killable-cancelled-tasks (d/db conn))]
          (log/warn "killing cancelled task " (:instance/task-id task))
          @(d/transact conn [[:db/add (:db/id task) :instance/reason
                              [:reason/name :mesos-executor-terminated]]])
          (cc/kill-task compute-cluster (:instance/task-id task)))))
    {:error-handler (fn [e]
                      (log/error e "Failed to kill cancelled tasks!"))}))

(defn get-user->used-resources
  "Return a map from user'name to his allocated resources, in the form of
   {:cpus cpu :mem mem}
   If a user does NOT has any running jobs, then there is NO such
   user in this map.

   (get-user-resource-allocation [db user])
   Return a map from user'name to his allocated resources, in the form of
   {:cpus cpu :mem mem}
   If a user does NOT has any running jobs, all the values in the
   resource map is 0.0"
  ([db]
   (let [user->used-resources (->> (q '[:find ?j
                                        :in $
                                        :where
                                        [?j :job/state :job.state/running]]
                                      db)
                                   (map (fn [[eid]]
                                          (d/entity db eid)))
                                   (group-by :job/user)
                                   (map (fn [[user job-ents]]
                                          [user (util/sum-resources-of-jobs job-ents)]))
                                   (into {}))]
     user->used-resources))
  ([db user]
   (let [used-resources (->> (q '[:find ?j
                                  :in $ ?u
                                  :where
                                  [?j :job/state :job.state/running]
                                  [?j :job/user ?u]]
                                db user)
                             (map (fn [[eid]]
                                    (d/entity db eid)))
                             (util/sum-resources-of-jobs))]
     {user (if (seq used-resources)
             used-resources
             ;; Return all 0's for a user who does NOT have any running job.
             (zipmap (util/get-all-resource-types db) (repeat 0.0)))})))

(defn limit-over-quota-jobs
  "Filters task-ents, preserving at most (config/max-over-quota-jobs) that would exceed the user's quota"
  [task-ents quota]
  (let [over-quota-job-limit (config/max-over-quota-jobs)]
    (->> task-ents
         (map (fn [task-ent] [task-ent (util/job->usage (:job/_instance task-ent))]))
         (reductions (fn [[prev-task total-usage over-quota-jobs] [task-ent usage]]
                       (let [total-usage' (merge-with + total-usage usage)]
                         (if (util/below-quota? quota total-usage')
                           [task-ent total-usage' over-quota-jobs]
                           [task-ent total-usage' (inc over-quota-jobs)])))
                     [nil {} 0])
         (take-while (fn [[task-ent _ over-quota-jobs]] (<= over-quota-jobs over-quota-job-limit)))
         (map first)
         (filter (fn [task-ent] (not (nil? task-ent)))))))

(defn sort-jobs-by-dru-helper
  "Return a list of job entities ordered by the provided sort function"
  [pending-task-ents running-task-ents user->dru-divisors sort-task-scored-task-pairs sort-jobs-duration pool-name user->quota]
  (let [tasks (into (vec running-task-ents) pending-task-ents)
        task-comparator (util/same-user-task-comparator tasks)
        pending-task-ents-set (into #{} pending-task-ents)
        jobs (timers/time!
               sort-jobs-duration
               (->> tasks
                    (group-by util/task-ent->user)
                    (map (fn [[user task-ents]] (let [sorted-tasks (sort task-comparator task-ents)]
                                                  [user (limit-over-quota-jobs sorted-tasks (user->quota user))])))
                    (into (hash-map))
                    (sort-task-scored-task-pairs user->dru-divisors pool-name)
                    (filter (fn [[task _]] (contains? pending-task-ents-set task)))
                    (map (fn [[task _]] (:job/_instance task)))))]
    jobs))

(defn- sort-normal-jobs-by-dru
  "Return a list of normal job entities ordered by dru"
  [pending-task-ents running-task-ents user->dru-divisors timer pool-name user->quota]
  (sort-jobs-by-dru-helper pending-task-ents running-task-ents user->dru-divisors
                           dru/sorted-task-scored-task-pairs timer pool-name user->quota))

(defn- sort-gpu-jobs-by-dru
  "Return a list of gpu job entities ordered by dru"
  [pending-task-ents running-task-ents user->dru-divisors timer pool-name user->quota]
  (sort-jobs-by-dru-helper pending-task-ents running-task-ents user->dru-divisors
                           dru/sorted-task-cumulative-gpu-score-pairs timer pool-name user->quota))

(defn- pool-map
  "Given a collection of pools, and a function val-fn that takes a pool,
  returns a map from pool name to (val-fn pool)"
  [pools val-fn]
  (->> pools
       (pc/map-from-keys val-fn)
       (pc/map-keys :pool/name)))

(defn sort-jobs-by-dru-pool
  "Returns a map from job pool to a list of job entities, ordered by dru"
  [unfiltered-db]
  ;; This function does not use the filtered db when it is not necessary in order to get better performance
  ;; The filtered db is not necessary when an entity could only arrive at a given state if it was already committed
  ;; e.g. running jobs or when it is always considered committed e.g. shares
  ;; The unfiltered db can also be used on pending job entities once the filtered db is used to limit
  ;; to only those jobs that have been committed.
  (let [pool-name->pending-job-ents (group-by util/job->pool-name (util/get-pending-job-ents unfiltered-db))
        pool-name->pending-task-ents (pc/map-vals #(map util/create-task-ent %1) pool-name->pending-job-ents)
        pool-name->running-task-ents (group-by (comp util/job->pool-name :job/_instance)
                                               (util/get-running-task-ents unfiltered-db))
        pools (pool/all-pools unfiltered-db)
        using-pools? (-> pools count pos?)
        pool-name->user->dru-divisors (if using-pools?
                                        (pool-map pools (fn [{:keys [pool/name]}]
                                                          (share/create-user->share-fn unfiltered-db name)))
                                        {"no-pool" (share/create-user->share-fn unfiltered-db nil)})
        pool-name->sort-jobs-by-dru-fn (if using-pools?
                                         (pool-map pools (fn [{:keys [pool/dru-mode]}]
                                                           (case dru-mode
                                                             :pool.dru-mode/default sort-normal-jobs-by-dru
                                                             :pool.dru-mode/gpu sort-gpu-jobs-by-dru)))
                                         {"no-pool" sort-normal-jobs-by-dru})]
    (letfn [(sort-jobs-by-dru-pool-helper [[pool-name sort-jobs-by-dru]]
              (let [pending-tasks (pool-name->pending-task-ents pool-name)
                    running-tasks (pool-name->running-task-ents pool-name)
                    user->dru-divisors (pool-name->user->dru-divisors pool-name)
                    user->quota (quota/create-user->quota-fn unfiltered-db
                                                             (when using-pools? pool-name))
                    timer (timers/timer (metric-title "sort-jobs-hierarchy-duration" pool-name))]
                [pool-name (sort-jobs-by-dru pending-tasks running-tasks user->dru-divisors timer pool-name user->quota)]))]
      (into {} (map sort-jobs-by-dru-pool-helper) pool-name->sort-jobs-by-dru-fn))))

(timers/deftimer [cook-mesos scheduler filter-offensive-jobs-duration])

(defn is-offensive?
  [max-memory-mb max-cpus job]
  (let [{memory-mb :mem
         cpus :cpus} (util/job-ent->resources job)]
    (or (> memory-mb max-memory-mb)
        (> cpus max-cpus))))

(defn filter-offensive-jobs
  "Base on the constraints on memory and cpus, given a list of job entities it
   puts the offensive jobs into offensive-job-ch asynchronically and returns
   the inoffensive jobs.

   A job is offensive if and only if its required memory or cpus exceeds the
   limits"
  ;; TODO these limits should come from the largest observed host from Fenzo
  ;; .getResourceStatus on TaskScheduler will give a map of hosts to resources; we can compute the max over those
  [{max-memory-gb :memory-gb max-cpus :cpus} offensive-jobs-ch jobs]
  (timers/time!
    filter-offensive-jobs-duration
    (let [max-memory-mb (* 1024.0 max-memory-gb)
          is-offensive? (partial is-offensive? max-memory-mb max-cpus)
          inoffensive (remove is-offensive? jobs)
          offensive (filter is-offensive? jobs)]
      ;; Put offensive jobs asynchronically such that it could return the
      ;; inoffensive jobs immediately.
      (async/go
        (when (seq offensive)
          (log/info "Found" (count offensive) "offensive jobs")
          (async/>! offensive-jobs-ch offensive)))
      inoffensive)))

(defn make-offensive-job-stifler
  "It returns an async channel which will be used to receive offensive jobs expected
   to be killed / aborted.

   It asynchronically pulls offensive jobs from the channel and abort these
   offensive jobs by marking job state as completed."
  [conn]
  (let [offensive-jobs-ch (async/chan (async/sliding-buffer 256))]
    (async/thread
      (loop []
        (when-let [offensive-jobs (async/<!! offensive-jobs-ch)]
          (try
            (doseq [jobs (partition-all 32 offensive-jobs)]
              ;; Transact synchronously so that it won't accidentally put a huge
              ;; spike of load on the transactor.
              (async/<!!
                (datomic/transact-with-retries conn
                                               (mapv
                                                 (fn [job]
                                                   [:db/add [:job/uuid (:job/uuid job)]
                                                    :job/state :job.state/completed])
                                                 jobs))))
            (log/warn "Suppressed offensive" (count offensive-jobs) "jobs" (mapv :job/uuid offensive-jobs))
            (catch Exception e
              (log/error e "Failed to kill the offensive job!")))
          (recur))))
    offensive-jobs-ch))

(timers/deftimer [cook-mesos scheduler rank-jobs-duration])
(meters/defmeter [cook-mesos scheduler rank-jobs-failures])

(defn rank-jobs
  "Return a map of lists of job entities ordered by dru, keyed by pool.

   It ranks the jobs by dru first and then apply several filters if provided."
  [unfiltered-db offensive-job-filter]
  (timers/time!
    rank-jobs-duration
    (try
      (->> (sort-jobs-by-dru-pool unfiltered-db)
           ;; Apply the offensive job filter first before taking.
           (pc/map-vals offensive-job-filter)
           (pc/map-vals #(map util/job-ent->map %))
           (pc/map-vals #(remove nil? %)))
      (catch Throwable t
        (log/error t "Failed to rank jobs")
        (meters/mark! rank-jobs-failures)
        {}))))

(defn- start-jobs-prioritizer!
  [conn pool-name->pending-jobs-atom task-constraints trigger-chan]
  (let [offensive-jobs-ch (make-offensive-job-stifler conn)
        offensive-job-filter (partial filter-offensive-jobs task-constraints offensive-jobs-ch)]
    (util/chime-at-ch trigger-chan
                      (fn rank-jobs-event []
                        (reset! pool-name->pending-jobs-atom
                                (rank-jobs (d/db conn) offensive-job-filter))))))

(meters/defmeter [cook-mesos scheduler mesos-error])
(meters/defmeter [cook-mesos scheduler offer-chan-full-error])

(defn make-fenzo-scheduler
  [compute-cluster offer-incubate-time-ms fitness-calculator good-enough-fitness]
  (.. (TaskScheduler$Builder.)
      (disableShortfallEvaluation) ;; We're not using the autoscaling features
      (withLeaseOfferExpirySecs (max (-> offer-incubate-time-ms time/millis time/in-seconds) 1)) ;; should be at least 1 second
      (withRejectAllExpiredOffers)
      (withFitnessCalculator (config/fitness-calculator fitness-calculator))
      (withFitnessGoodEnoughFunction (reify Func1
                                       (call [_ fitness]
                                         (> fitness good-enough-fitness))))
      (withLeaseRejectAction (reify Action1
                               (call [_ lease]
                                 (let [offer (:offer lease)
                                       id (:id offer)]
                                   (log/debug "Fenzo is declining offer" offer)
                                   (try
                                     (decline-offers compute-cluster [id])
                                     (catch Exception e
                                       (log/error e "Unable to decline fenzos rejected offers")))))))
      (build)))

(defn persist-mea-culpa-failure-limit!
  "The Datomic transactor needs to be able to access this part of the
  configuration, so on cook startup we transact the configured value into Datomic."
  [conn limits]
  (when (map? limits)
    (let [default (:default limits)
          overrides (mapv (fn [[reason limit]] {:db/id [:reason/name reason]
                                                :reason/failure-limit limit})
                          (dissoc limits :default))]
      (when default
        @(d/transact conn [{:db/id :scheduler/config
                            :scheduler.config/mea-culpa-failure-limit default}]))
      (when (seq overrides)
        @(d/transact conn overrides))))
  (when (number? limits)
    @(d/transact conn [{:db/id :scheduler/config
                        :scheduler.config/mea-culpa-failure-limit limits}])))

(defn decline-offers-safe
  "Declines a collection of offers, catching exceptions"
  [compute-cluster offers]
  (try
    (decline-offers compute-cluster (map :id offers))
    (catch Exception e
      (log/error e "Unable to decline offers!"))))

(defn receive-offers
  [offers-chan match-trigger-chan compute-cluster pool-name offers]
  (doseq [offer offers]
    (histograms/update! (histograms/histogram (metric-title "offer-size-cpus" pool-name)) (get-in offer [:resources :cpus] 0))
    (histograms/update! (histograms/histogram (metric-title "offer-size-mem" pool-name)) (get-in offer [:resources :mem] 0)))
  (if (async/offer! offers-chan offers)
    (do
      (counters/inc! offer-chan-depth)
      (async/offer! match-trigger-chan :trigger)) ; :trigger is arbitrary, the value is ignored
    (do (log/warn "Offer chan is full. Are we not handling offers fast enough?")
        (meters/mark! offer-chan-full-error)
        (future
          (decline-offers-safe compute-cluster offers)))))

(let [in-order-queue-counter (counters/counter ["cook-mesos" "scheduler" "in-order-queue-size"])
      in-order-queue-timer (timers/timer ["cook-mesos" "scheduler" "in-order-queue-delay-duration"])
      parallelism 19 ; a prime number to potentially help make the distribution uniform
      processor-agents (->> #(agent nil)
                            (repeatedly parallelism)
                            vec)
      safe-call (fn agent-processor [_ body-fn]
                  (try
                    (body-fn)
                    (catch Exception e
                      (log/error e "Error processing mesos status/message."))))]
  (defn async-in-order-processing
    "Asynchronously processes the body-fn by queueing the task in an agent to ensure in-order processing."
    [order-id body-fn]
    (counters/inc! in-order-queue-counter)
    (let [timer-context (timers/start in-order-queue-timer)
          processor-agent (->> (mod (hash order-id) parallelism)
                               (nth processor-agents))]
      (send processor-agent safe-call
            #(do
               (timers/stop timer-context)
               (counters/dec! in-order-queue-counter)
               (body-fn))))))

(defn create-datomic-scheduler
  [{:keys [conn compute-cluster fenzo-fitness-calculator fenzo-floor-iterations-before-reset
           fenzo-floor-iterations-before-warn fenzo-max-jobs-considered fenzo-scaleback good-enough-fitness
           mea-culpa-failure-limit mesos-run-as-user agent-attributes-cache offer-incubate-time-ms
           pool-name->pending-jobs-atom rebalancer-reservation-atom task-constraints
           trigger-chans]}]

  (persist-mea-culpa-failure-limit! conn mea-culpa-failure-limit)

  (let [{:keys [match-trigger-chan rank-trigger-chan]} trigger-chans
        pools (pool/all-pools (d/db conn))
        pools' (if (-> pools count pos?)
                 pools
                 [{:pool/name "no-pool"}])
        pool-name->fenzo (pool-map pools' (fn [_] (make-fenzo-scheduler compute-cluster offer-incubate-time-ms
                                                                        fenzo-fitness-calculator good-enough-fitness)))
        {:keys [pool->resources-atom]}
        (reduce (fn [m pool-ent]
                  (let [pool-name (:pool/name pool-ent)
                        fenzo (pool-name->fenzo pool-name)
                        resources-atom
                        (make-offer-handler
                          conn fenzo pool-name->pending-jobs-atom agent-attributes-cache fenzo-max-jobs-considered
                          fenzo-scaleback fenzo-floor-iterations-before-warn fenzo-floor-iterations-before-reset
                          match-trigger-chan rebalancer-reservation-atom mesos-run-as-user pool-name compute-cluster)]
                    (-> m
                        (assoc-in [:pool->resources-atom pool-name] resources-atom))))
                {}
                pools')]
    (start-jobs-prioritizer! conn pool-name->pending-jobs-atom task-constraints rank-trigger-chan)
    {:pool-name->fenzo pool-name->fenzo
     :view-incubating-offers (fn get-resources-atom [p]
                               (deref (get pool->resources-atom p)))}))