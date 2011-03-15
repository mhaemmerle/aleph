;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"} aleph.redis
  (:use
    [aleph tcp]
    [lamina core connections]
    [aleph.redis protocol]))

(defn redis-client
  "Returns a function which represents a persistent connection to the Redis server
   located at :host.  The 'options' may also specify the :port and :charset used for this
   client.  The function expects a vector of strings and an optional timeout,
   in the form

   (f [\"set\" \"foo\" \"bar\"] timeout?)

   The function will return a result-channel representing the response.  To close the
   connection, use lamina.connections/close-connection."
  ([options]
     (let [options (merge options {:port 6379 :charset :utf-8})]
       (client
	 #(tcp-client (merge options {:frame (redis-codec (:charset options))}))
	 (str "redis @ " (:host options) ":" (:port options))))))

(defn enqueue-task
  "Enqueues a task onto a Redis queue. 'task' must be a printable Clojure data structure."
  [redis-client queue-name task]
  (redis-client ["lpush" queue-name (pr-str task)]))

(defn receive-task
  "Receives a task from one of the specified queues.  Returns a result-channel which will
   emit a value with the structure {:queue \"queue-name\", :task 1}."
  [redis-client & queue-names]
  (run-pipeline
    (redis-client (concat ["brpop"] queue-names [0]))
    #(hash-map :queue (first %) :task (read-string (second %)))))

(defn redis-stream
  "Returns a channel representing a stream from the Redis server located at :host. 'options'
   may also specify the :port and :charset used for this stream.

   Initially, the stream is not subscribed to any channels; to receive events, subscribe to
   channels using (subscribe stream & channel-names) or
   (pattern-subscribe stream & channel-patterns). To unsubscribe, use (unsubscribe ...) and
   (pattern-unsubscribe ...).

   Messages from the stream will be of the structure {:channel \"channel\", :message \"message\"}."
  ([options]
     (let [options (merge options {:port 6379 :charset :utf-8})
	   control-messages (channel)
	   stream (channel)
	   control-message-accumulator (atom [])]
       (receive-all control-messages
	 #(swap! control-message-accumulator conj %))
       (let [connection (persistent-connection
			  #(tcp-client (merge options {:frame (redis-codec (:charset options))}))
			  (str "redis stream @ " (:host options) ":" (:port options))
			  (fn [ch]
			    ;; NOTE: this is a bit of a race condition (subscription messages
			    ;; may be sent twice), but subscription messages are idempotent.
			    ;; Regardless, maybe clean this up.
			    (let [control-messages* (fork control-messages)]
			      (doseq [msg @control-message-accumulator]
				(enqueue ch msg))
			      (siphon control-messages* ch))
			    (siphon 
			      (->> ch
				(filter* #(and (sequential? %) (= "message" (-> % first str))))
				(map* #(hash-map :channel (nth % 1) :message (nth % 2))))
			      stream)))]
	 (with-meta
	   (splice stream control-messages)
	   {::close-fn (fn []
			 (close-connection connection)
			 (close stream)
			 (close control-messages))})))))

(defn subscribe
  "Subscribes a stream to one or more channels.  Corresponds to the SUBSCRIBE command."
  [redis-stream & channel-names]
  (doseq [c channel-names]
    (enqueue redis-stream ["subscribe" c])))

(defn pattern-subscribe
  "Subscribes a stream to zero or more channels matching the patterns given.  Corresponds to
   the PSUBSCRIBE command."
  [redis-stream & channel-patterns]
  (doseq [c channel-patterns]
    (enqueue redis-stream ["psubscribe" c])))

(defn unsubscribe
  "Unsubscribes a stream from one or more channels.  Corresponds to the UNSUBSCRIBE command."
  [redis-stream & channel-names]
  (doseq [c channel-names]
    (enqueue redis-stream ["unsubscribe" c])))

(defn pattern-unsubscribe
  "Unsubscribes a stream from zero or more channels matching the patterns given.  Corresponds
   to the PUNSUBSCRIBE command."
  [redis-stream & channel-patterns]
  (doseq [c channel-patterns]
    (enqueue redis-stream ["punsubscribe" c])))

(defn close-stream
  "Closes a Redis stream."
  [redis-stream]
  ((-> redis-stream meta ::close-fn)))





