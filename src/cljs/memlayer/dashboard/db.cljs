(ns memlayer.dashboard.db)

(def default-db
  {:route            nil
   :health           {:status nil :loading? false :error nil}
   :memory-stats     {:data nil :loading? false :error nil}
   :consistency      {:data nil :loading? false :error nil}
   :memories         {:items [] :loading? false :error nil
                      :params {:limit 20 :offset 0 :query nil :layer nil :namespace nil}}
   :selected-memory  nil
   :graph            {:memories [] :relationships [] :loading? false
                      :visible-layers #{:layer/domain :layer/concept :layer/fact :layer/episode}
                      :show-hierarchy? true
                      :hidden-rel-types #{}
                      :zoom-level 1.0
                      :selected-node-id nil
                      :highlighted-ids #{}
                      :highlighted-rel-ids #{}
                      :recall {:query "" :results nil :loading? false :error nil}}
   :playground       {:active-tab :retain
                      :retain {:request {:content "" :source "playground" :namespace nil}
                               :response nil :loading? false :error nil}
                      :recall {:params {:query "" :limit 10 :namespace nil}
                               :results nil :loading? false :error nil}
                      :file-upload {:status :closed :file-name nil :file-size nil
                                    :percentage 0 :chunks-retained nil
                                    :result nil :error nil}}
   :namespaces       {:items [] :loading? false
                      :create-modal {:open? false :name ""}
                      :delete-target nil :rename-target nil}
   :active-namespace "default"
   :auth             {:user nil :loading? true :id-token nil :active-api-key nil}
   :theme            :light
   :tokens           {:items [] :loading? false :error nil :new-token nil}
   :settings         {:data nil :loading? false :error nil :active-tab :general
                      :keys-form {:groq-key "" :openai-key ""}}
   :usage            {:data nil :loading? false :error nil :range "30d"}
   :pipeline         {:data nil :loading? false :error nil
                      :operations nil :ops-loading? false
                      :selected-op nil}})
