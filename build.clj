(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as shell]))

(def lib 'com.memlayer/memlayer)
(def class-dir "target/classes")
(def uber-file "target/memlayer.jar")

(def basis (delay (b/create-basis {:project "deps.edn"
                                   :aliases [:server]})))

;; Basis with :native alias — includes GraalVM SDK so superv.async's
;; native-image-build? macro detects it and uses dummy-supervisor.
(def native-basis (delay (b/create-basis {:project "deps.edn"
                                          :aliases [:native :server]})))

(defn- sh [& args]
  (let [{:keys [exit out]} (apply shell/sh args)]
    (when (zero? exit) (.trim out))))

(defn- generate-version-edn
  "Write resources/version.edn with git metadata."
  []
  (let [version  (or (System/getenv "GIT_VERSION") (sh "git" "describe" "--tags" "--always" "--dirty" "--match" "v*") "dev")
        git-sha  (or (System/getenv "GIT_SHA") (sh "git" "rev-parse" "--short" "HEAD") "unknown")
        built-at (.toString (java.time.Instant/now))]
    (spit "resources/version.edn"
          (pr-str {:version  version
                   :git-sha  git-sha
                   :built-at built-at}))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (generate-version-edn)
  (b/copy-dir {:src-dirs   ["src/clj" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :ns-compile ['memlayer.system 'memlayer.mcp.server]
                  :src-dirs  ["src/clj"]
                  :class-dir class-dir
                  :java-opts ["--add-modules" "jdk.incubator.vector"
                              "--enable-native-access=ALL-UNNAMED"]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'memlayer.system}))

(def local-uber-file "target/memlayer-local.jar")

(defn local-uber
  "Build uberjar for local mode (JVM). Includes dashboard static assets."
  [_]
  (clean nil)
  (generate-version-edn)
  (b/copy-dir {:src-dirs   ["src/clj" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :ns-compile ['memlayer.local]
                  :src-dirs  ["src/clj"]
                  :class-dir class-dir
                  :java-opts ["--add-modules" "jdk.incubator.vector"
                              "--enable-native-access=ALL-UNNAMED"]})
  (b/uber {:class-dir class-dir
           :uber-file local-uber-file
           :basis     @basis
           :main      'memlayer.local})
  (println "Built:" local-uber-file))

(defn native-uber
  "Build uberjar for GraalVM native-image. Uses :native alias during AOT
   so superv.async detects GraalVM and uses dummy-supervisor."
  [_]
  (clean nil)
  (generate-version-edn)
  (b/copy-dir {:src-dirs   ["src/clj" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @native-basis
                  :ns-compile ['memlayer.local]
                  :src-dirs  ["src/clj"]
                  :class-dir class-dir
                  :java-opts ["--add-modules" "jdk.incubator.vector"
                              "--enable-native-access=ALL-UNNAMED"]})
  ;; Uber uses regular basis (without GraalVM SDK) to avoid
  ;; native-image.properties conflict in the jar.
  (b/uber {:class-dir class-dir
           :uber-file local-uber-file
           :basis     @basis
           :main      'memlayer.local})
  (println "Built:" local-uber-file))
