(ns forma.hadoop.jobs.load-tseries-test
  (:use forma.hadoop.jobs.load-tseries
        [forma.trends.analysis :only (long-trend-shell short-trend-shell)]
        clojure.test
        midje.sweet
        cascalog.api))

(def ndvi1 [7417 7568 7930 8049 8039 8533 8260 8192 7968 7148 7724
            8800 8068 7680 7590 7882 8022 8194 8031 8100 7965 8538
            7881 8347 8167 5295 8000 7874 8220 8283 8194 7826 8698
            7838 8967 8136 7532 7838 8009 8136 8400 8219 8051 8091
            7718 8095 8391 7983 8236 8091 7937 7958 8147 8134 7813
            8146 7623 8525 8714 8058 6730 8232 7744 8030 8355 8216
            7879 8080 8201 7987 8498 7868 7852 7983 8135 8012 8195
            8157 7989 8372 8007 8081 7940 7712 7913 8021 8241 8041
            7250 7884 8105 8033 8340 8288 7691 7599 8480 8563 8033
            7708 7575 7996 7739 8058 7400 6682 7999 7655 7533 7904
            8328 8056 7817 7601 7924 7905 7623 7615 7560 7330 7878
            8524 8167 7526 7330 7325 7485 8108 7978 7035 7650])

(def ndvi2 [7417 7568 7930 8049 8039 8533 8260 8192 1968 7148 7724
            8811 8068 7680 7590 7882 8022 8194 8031 8100 7965 8538
            7881 8347 8167 5295 8000 7874 8220 8283 8194 7826 8698
            7838 8967 8136 7532 7438 8009 8136 8400 8219 8051 8091
            7718 8095 8391 7983 8236 8091 7937 7958 8147 8134 7813
            8146 7623 8525 8714 8058 6730 8232 7744 8030 8355 8216
            7879 8080 8201 7987 8498 7868 7852 7983 8135 8012 8195
            8157 7989 8372 8007 8081 7940 7712 7913 8021 8241 8041
            7250 7884 8105 8033 8140 8288 7691 7599 8480 8563 8033
            7708 7575 7996 7739 7058 7400 3682 7999 2655 7533 7904
            8328 8056 7817 7601 7924 7905 7623 7615 7560 7330 7878
            8524 8167 7526 7330 2325 2485 2108 2978 2035 7650])

(def test-data [["ndvi" "1000" "32" 8 6 100 100 361 491 ndvi1 ndvi2]
                ["ndvi" "1000" "32" 8 6 100 101 361 491 ndvi2 ndvi1]])

(def options-map
  {:ref-date  "2005-12-01"
   :est-start "2005-12-01"
   :est-end   "2010-12-01"
   :t-res     "32"
   :long-block 15
   :window     5})

(defn tester-casc
  [data]
  (<- [?h ?v ?s ?l ?refdata ?whizref]
      (data ?dset ?s-res ?t-res ?h ?v ?s ?l ?t-start ?t-end ?t-series ?cof)
      (short-trend-shell options-map ?t-start ?t-series :> ?refdata ?estdata)
      (long-trend-shell options-map ?t-start ?t-series ?cof :> ?whizref ?whizest)))
(fact
 "check to make sure that the cascalog query returns two results with the correct
  number of elements."
 (count
  (flatten
   (??- (tester-casc test-data)))) => 14)
