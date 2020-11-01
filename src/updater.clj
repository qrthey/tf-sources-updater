(ns updater
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
  [dir]
  (let [terraform-files
        (->> (file-seq (clojure.java.io/file dir))
             (map #(.getPath %))
             (filter #(and (not (str/includes? % ".terraform"))
                           (not (str/includes? % ".git"))
                           (str/ends-with? % ".tf"))))

        file->modules (->> terraform-files
                           (map (fn [terraform-file]
                                  (let [re-github-within-parenthesis
                                        #"\"([^\"]*github[^\"]*)\""

                                        find-github-urls
                                        #(->> %
                                              (re-seq re-github-within-parenthesis)
                                              (map second)
                                              set)]
                                    [terraform-file
                                     (let [contents (slurp terraform-file)]
                                       {:contents
                                        contents

                                        :referenced-modules
                                        (->> contents
                                             find-github-urls
                                             (map (fn [github-url]
                                                    (let [re-account-repository-reference
                                                          #"github.com:([^/]+)/([^.?]+).*\?ref=(.*)"

                                                          parts
                                                          (re-find re-account-repository-reference github-url)]
                                                      {:original-github-url github-url
                                                       :account (nth parts 1)
                                                       :repository (nth parts 2)
                                                       :tag (parse-tag (nth parts 3))})))
                                             set)})])))
                           (into {}))]
    (into {} (filter #(seq (:referenced-modules (val %))) file->modules))))

(defn find-available-module-tags
  [{:keys [account repository oauth-token] :or {oauth-token (System/getenv "GITHUB_TOKEN_ICE")}}]
  (let [nth-int #(Integer/parseInt (nth %1 %2))
        tags (-> (str "https://api.github.com/repos/" account "/" repository "/git/refs/tags")
                 (http-client/get
                   (when oauth-token
                     {:headers {"Authorization" (str "token " oauth-token)}}))
                 :body
                 (json/parse-string keyword))]
    (mapv #(parse-tag (subs (:ref %) (count "refs/tags/")))
          tags)))

(defn update-references
  [dir]
  (let [->module-id
        #(select-keys % [:account :repository])

        file-path->contents-and-module-refs
        (find-terraform-files-with-module-references dir)

        module-id->available-tags
        (->> (vals file-path->contents-and-module-refs)
               (map :referenced-modules)
               (apply concat)
               set
               (map (fn [module] [(->module-id module) (find-available-module-tags module)]))
               (into {}))]

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
          (spit file-path updated-contents))))))
