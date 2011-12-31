(ns forma.schema
  (:require [clojure.string :as s]
            [forma.utils :as u]
            [forma.reproject :as r]
            [forma.date-time :as date]))

(defn boundaries
  "Accepts a sequence of pairs of <initial time period, collection>
  and returns the maximum start period and the minimum end period. For
  example:

    (boundaries [0 [1 2 3 4] 1 [2 3 4 5]]) => [1 4]"
  [pair-seq]
  {:pre [(even? (count pair-seq))]}
  (reduce (fn [[lo hi] [x0 ct]]
            [(max lo x0) (min hi ct)])
          (for [[x0 seq] (partition 2 pair-seq)]
            [x0 (+ x0 (count seq))])))

(defn adjust
  "Appropriately truncates the incoming timeseries values (paired with
  the initial integer period), and outputs a new start and both
  truncated series. For example:

    (adjust 0 [1 2 3 4] 1 [2 3 4 5])
    ;=> (1 [2 3 4] [2 3 4])"
  [& pairs]
  {:pre [(even? (count pairs))]}
  (let [[bottom top] (boundaries pairs)]
    (cons bottom
          (for [[x0 seq] (partition 2 pairs)]
            (into [] (u/trim-seq bottom top x0 seq))))))

;; ## Time Series

(defn timeseries-value
  ([start-idx series]
     (let [elems (count series)]
       (timeseries-value start-idx
                         (dec (+ start-idx elems))
                         series)))
  ([start-idx end-idx series]
     (when (seq series)
       {:start-idx start-idx
        :end-idx   end-idx
        :series    (vec series)})))

(defn adjust-timeseries
  "Takes in any number of timeseries objects, and returns a new
  sequence of appropriately truncated TimeSeries objects."
  [& tseries]
  (let [[start & ts-seq] (->> tseries
                              (mapcat (juxt :start-idx :series))
                              (apply adjust))]
    (map (partial timeseries-value start)
         ts-seq)))

;; ### Fire Values

(def example-fire-value
  {:temp-330   "number of fires w/ (> T 330 degrees Kelvin)"
   :conf-50    "number of fires w/ confidence above 50."
   :both-preds "number of fires w/ both."
   :count      "number of fires on the given day."})

(defn fire-value
  [t-above-330 c-above-50 both-preds count]
  {:temp-330 t-above-330
   :conf-50 c-above-50
   :both-preds both-preds
   :count count})

(defn extract-fields
  "Returns a vector containing the value of the `temp-330`, `conf-50`,
  `both-preds` and `count` fields of the supplied fire tuple."
  [tuple]
  (map tuple [:temp-330 :conf-50 :both-preds :count]))

(defn add-fires
  "Returns a new `FireTuple` object generated by summing up the fields
  of each of the supplied `FireTuple` objects."
  [& f-tuples]
  (apply merge-with + f-tuples))

(defn adjust-fires
  "Returns the section of fires data found appropriate based on the
  information in the estimation parameter map."
  [{:keys [est-start est-end t-res]} f-series]
  (let [[start end] (for [pd [est-start est-end]]
                      (date/datetime->period "32" pd))]
    [(->> (:series f-series)
          (u/trim-seq start (inc end) (:start-idx f-series))
          (timeseries-value start))]))

;; # Compound Objects

(def example-forma-value
  {:fire-value "fire value."
   :short-drop "Short term drop in NDVI."
   :long-drop  "Long term drop in NDVI."
   :t-stat     "t-statistic for the relevant month."})

(defn forma-value
  [fire short long t-stat]
  {:fire-value (or fire (fire-value 0 0 0 0))
   :short-drop short
   :long-drop  long
   :t-stat     t-stat})

(defn unpack-forma-val
  "Returns a vector containing the fire value, short drop,
  long drop and t-stat fields of the supplied `FormaValue`."
  [forma-val]
  (map forma-val [:fire-value :short-drop :long-drop :t-stat]))

;; ## Neighbor Values

(def example-forma-neighbor-value
  {:fire-value     "fire value."
   :num-neighbors  "Number of non-nil neighbors."
   :avg-short-drop "Average..."
   :min-short-drop "min..."
   :avg-long-drop  "Average..."
   :min-long-drop  "min..."
   :avg-t-stat     "Average..."
   :min-t-stat     "min..."})

(defn neighbor-value
  "Accepts either a forma value or a sequence of sub-values."
  ([{:keys [fire-value short-drop long-drop t-stat]}]
     (neighbor-value fire-value 1
                     short-drop short-drop
                     long-drop long-drop
                     t-stat t-stat))
  ([fire neighbors avg-short min-short avg-long min-long avg-stat min-stat]
     {:fire-value     fire
      :neighbor-count neighbors
      :avg-short-drop avg-short
      :min-short-drop min-short
      :avg-long-drop  avg-long
      :min-long-drop  min-long
      :avg-t-stat     avg-stat
      :min-t-stat     min-stat}))

(defn unpack-neighbor-val
  [neighbor-val]
  (map neighbor-val
       [:fire-value
        :neighbor-count
        :avg-short-drop
        :min-short-drop
        :avg-long-drop 
        :min-long-drop 
        :avg-t-stat    
        :min-t-stat]))

(defn merge-neighbors
  "Merges the supplied instance of `FormaValue` into the existing
  aggregate collection of `FormaValue`s represented by
  `neighbor-val`. (`neighbors` must be an instance of
  neighbor-value)"
  [neighbors forma-val]
  (let [n-count  (:neighbor-count neighbors)
        [fire short long t-stat] (unpack-forma-val forma-val)]
    (-> neighbors
        (update-in [:fire-value]     add-fires fire)
        (update-in [:neighbor-count] inc)
        (update-in [:avg-short-drop] u/weighted-mean n-count short 1)
        (update-in [:avg-long-drop]  u/weighted-mean n-count long 1)
        (update-in [:avg-t-stat]     u/weighted-mean n-count t-stat 1)
        (update-in [:min-short-drop] min short)
        (update-in [:min-long-drop]  min long)
        (update-in [:min-t-stat]     min t-stat))))

(defn combine-neighbors
  "Returns a new forma neighbor value generated by merging together
   each entry in the supplied sequence of forma values."
  [[x & more]]
  (if x
    (reduce merge-neighbors (neighbor-value x) more)
    (neighbor-value (fire-value 0 0 0 0) 0 0 0 0 0 0 0)))

(defn textify
  "Converts the supplied coordinates, forma value and forma neighbor
  value into a line of text suitable for use in STATA."
  [forma-val neighbor-val]
  (let [[fire-val s-drop l-drop t-drop] (unpack-forma-val forma-val)
        [fire-sum ct short-mean short-min
         long-mean long-min t-mean t-min] (unpack-neighbor-val neighbor-val)
        [k330 c50 ck fire] (extract-fields fire-val)
        [k330-n c50-n ck-n fire-n] (extract-fields fire-sum)]
    (->> [k330 c50 ck fire s-drop l-drop t-drop
          k330-n c50-n ck-n fire-n
          ct short-mean short-min long-mean long-min t-mean t-min]
         (map (fn [num]
                (let [l (long num)]
                  (if (== l num) l num))))
         (s/join \tab))))

;; ## Location

(def example-chunk-location
  {:spatial-res "Spatial resolution (modis)"
   :mod-h "horizontal modis coordinate."
   :mod-v "Vertical modis coordinate."
   :index "Chunk index within the modis tile."
   :size  "Number of pixels in the chunk."})

(defn chunk-location
  [spatial-res mod-h mod-v idx size]
  {:spatial-res spatial-res
   :mod-h       mod-h
   :mod-v       mod-v
   :index       idx
   :size        size})

(def example-pixel-location
  {:spatial-res "Spatial resolution (modis)"
   :mod-h "horizontal modis coordinate."
   :mod-v "Vertical modis coordinate."
   :sample "Sample (column) within modis tile."
   :line  "Line (row) within modis tile."})

(defn pixel-location
  [spatial-res mod-h mod-v sample line]
  {:spatial-res spatial-res
   :mod-h       mod-h
   :mod-v       mod-v
   :sample      sample
   :line        line})

(defn unpack-pixel-location [loc]
  (map loc [:spatial-res :mod-h :mod-v :sample :line]))

(defn chunkloc->pixloc
  "Accepts a chunk location and a pixel index within that location and
  returns a pixel location."
  [{:keys [spatial-res size index mod-h mod-v]} pix-idx]
  (apply pixel-location spatial-res mod-h mod-v
         (r/tile-position spatial-res size index pix-idx)))

;; ## Data Chunks

(defn chunk-value
  [dataset t-res date location data-value]
  (let [chunk {:temporal-res t-res
               :location     location
               :dataset      dataset
               :value        data-value}]
    (if-not date
      chunk
      (assoc chunk :date date))))

(defn unpack-chunk-val
  "Used by timeseries. Returns `[dataset-name t-res date collection]`,
   where collection is a vector."
  [chunk]
  (map chunk [:dataset :temporal-res :date :location :value]))

(defn forma-seq
  "Accepts a number of timeseries of equal length and starting
  position, and converts the first entry in each timeseries to a forma
  value, for all first values and on up the sequence. Series must be
  supplied as specified by the arguments for `forma-value`. For
  example:

    (forma-seq fire-series short-series long-series t-stat-series)"
  [& in-series]
  [(->> in-series
        (map #(or (:series %) (repeat %)))
        (apply map forma-value))])
