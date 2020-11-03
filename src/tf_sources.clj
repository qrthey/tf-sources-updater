(ns tf-sources
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http-client]))

(defn parse-tag
  "Tries to parse a tag into an optional prefix string, major, minor and
  patch integers, and an optional -rc integer.

  Some examples to explain:

  v2.13.3   ;; no rc value
  => {:unparsed-tag \"v2.13.3\"
      :prefix \"v\"
      :major 2
      :minor 13
      :patch 3}

  v1.0 ;; no patch nor rc value
  => {:unparsed-tag \"v1.0\"
      :prefix \"v\"
      :major 1
      :minor 0}

  v12.1.33-rc14
  => {:unparsed-tag \"v12.1.33-rc14\"
      :prefix \"v\"
      :major 12
      :minor 1
      :patch 33
      :rc 14}
  "
  [tag]
  (let [part->idx {:major 2
                   :minor 3
                   :patch 4}

        re-prefix-major-minor-patch-rc
        #"([^\d]*)(\d+)?\.?(\d+)?\.?(\d+)?(-rc\d+)?"

        parts (re-find re-prefix-major-minor-patch-rc tag)

        without-rc (reduce
                     (fn [acc [key idx]]
                       (if-let [v-str (nth parts idx)]
                         (assoc acc key (Integer/parseInt v-str))
                         acc))
                     {:unparsed-tag tag
                      :prefix (nth parts 1)}
                     part->idx)]

    (conj without-rc (when-let [rc-str (nth parts 5)]
                       [:rc (Integer/parseInt
                              (nth (re-find #"-rc(\d+)" rc-str)
                                   1))]))))

(defn find-terraform-files-with-module-references
  "Returns a map of file-path to maps of file content and referenced
  modules."
  [dir]
  (let [terraform-files
        (->> (file-seq (clojure.java.io/file dir))
             (map #(.getPath %))
             (filter #(and (not (str/includes? % ".terraform"))
                           (not (str/includes? % ".git"))
                           (str/ends-with? % ".tf"))))]
    (->> terraform-files
         (map (fn [terraform-file]
                (let [re-github-within-parenthesis
                      #"\"([^\s\"]*github[^\"]*)\""

                      find-github-urls
                      #(->> %
                            (re-seq re-github-within-parenthesis)
                            (map second)
                            set)

                      contents
                      (slurp terraform-file)

                      referenced-modules
                      (->> contents
                           find-github-urls
                           (map (fn [github-url]
                                  (let [re-account-repository-reference
                                        #"github.com:([^/]+)/([^.?/]+).*\?ref=(.*)"

                                        parts
                                        (re-find re-account-repository-reference github-url)]
                                    {:original-github-url github-url
                                     :account (nth parts 1)
                                     :repository (nth parts 2)
                                     :tag (parse-tag (nth parts 3))})))
                           set)]
                  [terraform-file {:contents contents
                                   :referenced-modules referenced-modules}])))
         (filter #(seq (:referenced-modules (second %))))
         (into {}))))

(defn find-available-module-tags
  "Returns a vector of tag data retrieved from the specified github
  account/repository."
  [{:keys [account repository]}]
  (try
    (let [nth-int #(Integer/parseInt (nth %1 %2))
          tags (-> (str "https://api.github.com/repos/" account "/" repository "/git/refs/tags")
                   (http-client/get
                     (when-let [oauth-token (System/getenv "GITHUB_TOKEN")]
                       {:headers {"Authorization" (str "token " oauth-token)}}))
                   :body
                   (json/parse-string keyword))]
      (mapv #(parse-tag (subs (:ref %) (count "refs/tags/")))
            tags))
    (catch Exception exc
      (println (str "could not fetch tags for " account "/" repository))
      (throw exc)))
  )

(defn update-references
  "Patches all github urls used as sources in .tf files under dir with
  updated tag versions."
  [{:keys [dir]}]
  (when-not dir
    (println "please specify the dir option")
    (System/exit -1))
  (let [->module-id
        #(select-keys % [:account :repository])

        file-path->contents-and-module-refs
        (find-terraform-files-with-module-references dir)

        unique-module-ids
        (->> (vals file-path->contents-and-module-refs)
             (map :referenced-modules)
             (apply concat)
             (map ->module-id)
             set)

        _ (do
            (println (str "* found " (count unique-module-ids) " referenced module repositories"
                          " across " (count file-path->contents-and-module-refs)
                          " files with module references"))
            (print "* loading module tags from github: ") (flush))
        
        module-id->available-tags
        (->> unique-module-ids
             (map (fn [module-id]
                    (print ".")
                    (flush)
                    [module-id (find-available-module-tags module-id)]))
             (into {}))]
    (println)
    (println "* patching files:")
    (doseq [[file-path contents-and-module-refs] file-path->contents-and-module-refs]
      (let [updated-contents (reduce
                               (fn [acc module]
                                 (str/replace acc
                                              (:original-github-url module)
                                              (str/replace (:original-github-url module)
                                                           (:unparsed-tag (:tag module))
                                                           (:unparsed-tag (last (get module-id->available-tags
                                                                                     (->module-id module)))))))
                               (:contents contents-and-module-refs)
                               (:referenced-modules contents-and-module-refs))]
        (when (not= (:contents contents-and-module-refs) updated-contents)
          (println (str "  - writing " file-path))
          (spit file-path updated-contents))))
    (println "* done")))
