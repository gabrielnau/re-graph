(ns re-graph.core-test
  (:require [re-graph.core :as re-graph]
            [re-frame.core :as re-frame]
            [re-frame.db :refer [app-db]]
            [day8.re-frame.test :refer-macros [run-test-sync run-test-async wait-for]]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [devcards.core :refer-macros [deftest]]))

(def on-ws-message @#'re-graph/on-ws-message)
(def on-open @#'re-graph/on-open)
(def on-close @#'re-graph/on-close)

(re-frame/reg-fx
 ::re-graph/connect-ws
 (fn [& args]
   ((on-open ::websocket-connection))))

(deftest subscription-test
  (run-test-sync
   (re-frame/dispatch [::re-graph/init])

   (let [expected-subscription-payload {:id "my-sub"
                                        :type "start"
                                        :payload {:query "subscription { things { id } }"
                                                  :variables {:some "variable"}}}
         expected-unsubscription-payload {:id "my-sub"
                                          :type "stop"}]

     (testing "Subscriptions can be registered"

       (re-frame/reg-fx
        ::re-graph/send-ws
        (fn [[ws payload]]
          (is (= ::websocket-connection ws))
          (is (= expected-subscription-payload
                 payload))))

       (re-frame/dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]])

       (is (= [::on-thing]
              (get-in @app-db [:re-graph :subscriptions "my-sub" :callback])))

       (testing "messages from the WS are sent to the callback"

         (let [expected-response-payload {:things [{:id 1} {:id 2}]}]
           (re-frame/reg-event-db
            ::on-thing
            (fn [db [_ payload]]
              (assoc db ::thing payload)))

           (on-ws-message (clj->js {:data (js/JSON.stringify
                                           (clj->js {:type "data"
                                                     :id "my-sub"
                                                     :payload {:data expected-response-payload}}))}))

           (is (= expected-response-payload
                  (::thing @app-db)))))

       (testing "and unregistered"
         (re-frame/reg-fx
          ::re-graph/send-ws
          (fn [[ws payload]]
            (is (= ::websocket-connection ws))
            (is (= expected-unsubscription-payload
                   payload))))

         (re-frame/dispatch [::re-graph/unsubscribe :my-sub])

         (is (nil? (get-in @app-db [:re-graph :subscriptions "my-sub"]))))))))

(deftest websocket-lifecycle-test
  (run-test-sync

   (re-frame/reg-fx
    ::re-graph/connect-ws
    (constantly nil))

   (re-frame/dispatch [::re-graph/init])

   (let [expected-subscription-payload {:id "my-sub"
                                        :type "start"
                                        :payload {:query "subscription { things { id } }"
                                                  :variables {:some "variable"}}}]

     (testing "messages are queued when websocket isn't ready"

       (re-frame/dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]])

       (is (= 1 (count (get-in @app-db [:re-graph :websocket :queue]))))

       (testing "and sent when websocket opens"

         (re-frame/reg-fx
          ::re-graph/send-ws
          (fn [[ws payload]]
            (is (= ::websocket-connection ws))
            (is (= expected-subscription-payload
                   payload))))

         ((on-open ::websocket-connection))

         (is (empty? (get-in @app-db [:re-graph :websocket :queue]))))))))

(deftest websocket-reconnection-test
  (run-test-async
   (testing "websocket reconnects when disconnected"
     (re-frame/dispatch-sync [::re-graph/init {:ws-reconnect-timeout 1}])

     (wait-for
      [::re-graph/on-ws-open]
      (is (get-in @app-db [:re-graph :websocket :ready?]))

      (on-close)
      (wait-for
       [::re-graph/on-ws-close]
       (is (false? (get-in @app-db [:re-graph :websocket :ready?])))

       (wait-for [::re-graph/on-ws-open]
                 (is (get-in @app-db [:re-graph :websocket :ready?]))))))))

(deftest http-query-test
  (run-test-sync
   (let [expected-http-url "http://foo.bar/graph-ql"]
     (re-frame/dispatch [::re-graph/init {:http-url expected-http-url}])

     (let [expected-query-payload {:query "query { things { id } }"
                                   :variables {:some "variable"}}
           expected-response-payload {:things [{:id 1} {:id 2}]}]

       (testing "Requests can be made"

         (re-frame/reg-fx
          ::re-graph/send-http
          (fn [[http-url {:keys [payload]} callback-fn]]
            (is (= expected-query-payload
                   payload))

            (is (= expected-http-url http-url))

            (callback-fn {:data expected-response-payload})))

         (re-frame/reg-event-db
          ::on-thing
          (fn [db [_ payload]]
            (assoc db ::thing payload)))

         (re-frame/dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

         (testing "responses are sent to the callback"
           (is (= expected-response-payload
                  (::thing @app-db)))))))))