(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as shell]))

(def lib 'com.memlayer/memlayer)
(def core-lib 'com.memlayer/memlayer-core)
(def class-dir "target/classes")
(def uber-file "target/memlayer.jar")

;; Uberjar basis — includes server deps for the full application.
(def basis (delay (b/create-basis {:project "deps.edn"
                                   :aliases [:server]})))

;; Basis with :native alias — includes GraalVM SDK so superv.async's
;; native-image-build? macro detects it and uses dummy-supervisor.
(def native-basis (delay (b/create-basis {:project "deps.edn"
                                          :aliases [:native :server]})))

;; Library basis — core deps only, no server/HTTP deps.
(def lib-basis (delay (b/create-basis {:project "deps.edn"})))

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

(defn- lib-version
  "Derive a Maven-compatible version from git tags. Strips leading 'v'."
  []
  (let [raw (or (System/getenv "LIB_VERSION")
                (sh "git" "describe" "--tags" "--always" "--match" "v*")
                "0.0.0-SNAPSHOT")]
    (cond-> raw
      (.startsWith raw "v") (subs 1))))

(defn clean [_]
  (b/delete {:path "target"}))

;; -- Library JAR --

(defn jar
  "Build a thin library JAR for Clojars distribution."
  [_]
  (clean nil)
  (generate-version-edn)
  (let [version  (lib-version)
        jar-path (format "target/%s-%s.jar" (name core-lib) version)]
    (b/write-pom {:class-dir class-dir
                  :lib       core-lib
                  :version   version
                  :basis     @lib-basis
                  :src-dirs  ["src"]
                  :scm       {:url                 "https://github.com/memlayer/memlayer"
                              :connection          "scm:git:https://github.com/memlayer/memlayer.git"
                              :developerConnection "scm:git:ssh://git@github.com/memlayer/memlayer.git"
                              :tag                 (str "v" version)}
                  :pom-data  [[:description "Memory layer for AI applications — retain, recall, reflect, forget"]
                              [:url "https://github.com/memlayer/memlayer"]
                              [:licenses
                               [:license
                                [:name "AGPL-3.0-or-later"]
                                [:url "https://www.gnu.org/licenses/agpl-3.0.html"]]]]})
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    ;; Remove files that would conflict with library consumers' classpath
    (b/delete {:path (str class-dir "/logback.xml")})
    (b/delete {:path (str class-dir "/logback-mcp.xml")})
    (b/delete {:path (str class-dir "/public")})
    (b/jar {:class-dir class-dir
            :jar-file  jar-path})
    (println "Built:" jar-path)))

(defn install
  "Install the library JAR to the local Maven repository (~/.m2)."
  [_]
  (let [version  (lib-version)
        jar-path (format "target/%s-%s.jar" (name core-lib) version)
        dd       (requiring-resolve 'deps-deploy.deps-deploy/deploy)]
    (when-not (.exists (java.io.File. jar-path))
      (throw (ex-info (str "JAR not found: " jar-path ". Run `jar` task first.") {})))
    (dd {:installer :local
         :artifact  (b/resolve-path jar-path)
         :pom-file  (b/pom-path {:lib core-lib :class-dir class-dir})})
    (println "Installed" core-lib version "to local Maven repo")))

(defn deploy
  "Deploy the library JAR to Clojars. Requires CLOJARS_USERNAME and CLOJARS_PASSWORD env vars."
  [_]
  (let [version  (lib-version)
        jar-path (format "target/%s-%s.jar" (name core-lib) version)
        dd       (requiring-resolve 'deps-deploy.deps-deploy/deploy)]
    (when-not (.exists (java.io.File. jar-path))
      (throw (ex-info (str "JAR not found: " jar-path ". Run `jar` task first.") {})))
    (dd {:installer :remote
         :artifact  (b/resolve-path jar-path)
         :pom-file  (b/pom-path {:lib core-lib :class-dir class-dir})})
    (println "Deployed" core-lib version "to Clojars")))

;; -- Uberjar --

(defn uber
  "Build uberjar. Includes dashboard static assets."
  [_]
  (clean nil)
  (generate-version-edn)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :ns-compile ['memlayer.local]
                  :src-dirs  ["src"]
                  :class-dir class-dir
                  :java-opts ["--add-modules" "jdk.incubator.vector"
                              "--enable-native-access=ALL-UNNAMED"]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'memlayer.local})
  (println "Built:" uber-file))

(defn native-uber
  "Build uberjar for GraalVM native-image. Uses :native alias during AOT
   so superv.async detects GraalVM and uses dummy-supervisor."
  [_]
  (clean nil)
  (generate-version-edn)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @native-basis
                  :ns-compile ['memlayer.local]
                  :src-dirs  ["src"]
                  :class-dir class-dir
                  :java-opts ["--add-modules" "jdk.incubator.vector"
                              "--enable-native-access=ALL-UNNAMED"]})
  ;; Uber uses regular basis (without GraalVM SDK) to avoid
  ;; native-image.properties conflict in the jar.
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'memlayer.local})
  (println "Built:" uber-file))
