(ns memlayer.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.cli :as cli]
            [memlayer.config :as config]))

(deftest parse-args-returns-parsed-options
  (testing "parses --port"
    (let [{:keys [options errors]} (cli/parse-args ["--port" "9090"])]
      (is (nil? errors))
      (is (= 9090 (:port options)))))

  (testing "parses -p shorthand"
    (let [{:keys [options errors]} (cli/parse-args ["-p" "9090"])]
      (is (nil? errors))
      (is (= 9090 (:port options)))))

  (testing "parses --namespace"
    (let [{:keys [options errors]} (cli/parse-args ["--namespace" "work"])]
      (is (nil? errors))
      (is (= "work" (:namespace options)))))

  (testing "parses -n shorthand"
    (let [{:keys [options errors]} (cli/parse-args ["-n" "work"])]
      (is (nil? errors))
      (is (= "work" (:namespace options)))))

  (testing "parses --idle-timeout"
    (let [{:keys [options errors]} (cli/parse-args ["--idle-timeout" "30"])]
      (is (nil? errors))
      (is (= 30 (:idle-timeout options)))))

  (testing "parses combined flags"
    (let [{:keys [options errors]} (cli/parse-args ["--port" "9090" "--namespace" "work"])]
      (is (nil? errors))
      (is (= 9090 (:port options)))
      (is (= "work" (:namespace options)))))

  (testing "empty args returns empty options"
    (let [{:keys [options errors]} (cli/parse-args [])]
      (is (nil? errors))
      (is (nil? (:port options)))
      (is (nil? (:namespace options)))))

  (testing "parses --help"
    (let [{:keys [options]} (cli/parse-args ["--help"])]
      (is (true? (:help options))))))

(deftest parse-args-reports-errors
  (testing "invalid port value"
    (let [{:keys [errors]} (cli/parse-args ["--port" "abc"])]
      (is (seq errors))))

  (testing "invalid port number"
    (let [{:keys [errors]} (cli/parse-args ["--port" "-1"])]
      (is (seq errors))))

  (testing "invalid idle-timeout"
    (let [{:keys [errors]} (cli/parse-args ["--idle-timeout" "xyz"])]
      (is (seq errors)))))

(deftest cli->config-overrides-maps-options
  (testing "port maps to [:server :port]"
    (is (= {:server {:port 9090}}
           (cli/cli->config-overrides {:port 9090}))))

  (testing "empty options produces empty overrides"
    (is (= {} (cli/cli->config-overrides {}))))

  (testing "namespace is not included in config overrides"
    (is (= {} (cli/cli->config-overrides {:namespace "work"}))))

  (testing "port with other non-config options"
    (is (= {:server {:port 8080}}
           (cli/cli->config-overrides {:port 8080 :namespace "work"})))))

(deftest load-config-with-overrides
  (testing "port override is applied to loaded config"
    (let [cfg (cli/cli->config-overrides {:port 9999})
          loaded (config/load-config "config.edn" cfg)]
      (is (= 9999 (get-in loaded [:server :port])))))

  (testing "nil overrides behaves like no overrides"
    (let [cfg (config/load-config "config.edn" nil)]
      (is (map? cfg))
      (is (int? (get-in cfg [:server :port]))))))
