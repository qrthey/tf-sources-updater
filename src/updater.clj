(ns updater
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http-client]))

#_(require '[clojure.java.shell :as shell])
#_(shell/sh "pwd")

(defn update-module-sources
  [dir]
  (let [terraform-files
        (->> (file-seq (clojure.java.io/file dir))
             (filter #(and (not (str/includes? (.getPath %) ".terraform"))
                           (not (str/includes? (.getPath %) ".git"))
                           (str/ends-with? (.getName %) ".tf"))))

        github-refs (->> terraform-files
                         (mapcat #(re-seq #"\"([^\"]*github[^\"]*)\"" (slurp %)))
                         (map second)
                         set
                         sort
                         (map (fn [url]
                                {:module-github-url (subs url 0 (str/index-of url "?"))
                                 :version (mapv #(Integer/parseInt %) (rest (re-find #"v(\d+)\.(\d+)\.(\d+)" url)))})))]
    (println "module sources:\n")
    (doseq [github-ref github-refs]
      (println (str "- module source '" (:module-github-url github-ref) "'"
                    "\n  - current version: " (str/join "." (:version github-ref)) "\n")))))

(defn get-available-tag-versions
  [{:keys [account repository]}]
  (let [nth-int #(Integer/parseInt (nth %1 %2))
        tags (-> (str "https://api.github.com/repos/" account "/" repository "/git/refs/tags")
                 (http-client/get
                   (when-let [oath-token (System/getenv "GITHUB_TOKEN_ICE")]
                     {:headers {"Authorization" (str "token " oath-token)}}))
                 :body
                 (json/parse-string keyword))

        parsed-tags
        (map (fn [tag]
               (->> (:ref tag)
                    (re-find #"refs/tags/([^\d]*)(\d+)\.(\d+)\.(\d+)(-rc\d+)?")
                    (#(hash-map :tag (nth (re-find #"refs/tags/(.*)" (nth % 0)) 1)
                                :prefix (nth % 1)
                                :major (nth-int % 2)
                                :minor (nth-int % 3)
                                :patch (nth-int % 4)
                                :rc (if-let [rc-str (nth % 5)]
                                      (nth-int (re-find #"-rc(\d+)" rc-str) 1))))))
             tags)]

    parsed-tags))

(comment
  ;; a nicely sorted table of tag information
  (pprint/print-table
    [:tag :prefix :major :minor :patch :rc]
    (reverse
      (sort-by (juxt :major :minor :patch #(or (:rc %) "0"))
               (get-available-tag-versions
                 {:account "skm-ice"
                  :repository "terraform-stack-team-iceout"}))))
  
  ;; a shell script
  ;; curl -H "Authorization: token OAUTH-TOKEN" https://api.github.com/repos/skm-ice/terraform-stack-team-iceout/git/refs/tags

  ;; found github.url is parsed into
  ;; - account
  ;; - repository
  ;; - current version
  ;;   - prefix
  ;;   - main-version
  ;;   - minor version
  ;;   - patch version


  ;; account and repository are resolved to
  ;; - list of available versions each having
  ;;   - prefix
  ;;   - main-version
  ;;   - minor version
  ;;   - patch version
  )
