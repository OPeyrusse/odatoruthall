(ns odato.review.diff-test
  "The diff a review read: files, hunks and numbered lines, what a remark may
  point at, and the lines shown around it."
  (:require [clojure.test :refer [deftest is testing]]
            [odato.demo.reviews :as demo]
            [odato.review.diff :as diff]))

(def files (diff/parse demo/diff))

(deftest a-diff-is-read-file-by-file
  (is (= ["src/main/java/acme/widget/Renderer.java" "src/main/java/acme/widget/SnapshotCache.java"]
         (map :file files)))
  (testing "a new file has no old path"
    (is (nil? (:old-file (second files))))
    (is (= "src/main/java/acme/widget/Renderer.java" (:old-file (first files)))))
  (testing "lines are numbered on their sides"
    (let [lines (:lines (first (:hunks (first files))))]
      (is (= {:kind :ctx :old 8 :new 8 :text "import java.util.Locale;"} (first lines)))
      (is (= {:kind :del :old 11 :new nil :text "  private Snapshot last;"} (nth lines 3)))
      (is (= {:kind :add :old nil :new 12 :text "  private static final Duration TTL = Duration.ofMinutes(5);"}
             (nth lines 5)))
      (is (= {:kind :ctx :old 12 :new 14 :text "  private final Theme theme;"} (nth lines 7)))))
  (testing "the hunk heading is kept"
    (is (= "import java.util.List;" (:header (first (:hunks (first files)))))))
  (testing "a deleted file keeps its old path"
    (is (= "gone.txt" (:file (first (diff/parse (str "diff --git a/gone.txt b/gone.txt\n"
                                                     "deleted file mode 100644\n"
                                                     "--- a/gone.txt\n+++ /dev/null\n"
                                                     "@@ -1 +0,0 @@\n-bye\n")))))))
  (testing "nothing, or no hunk, is no file with lines"
    (is (= [] (diff/parse "")))
    (is (= [] (mapcat :hunks (diff/parse "diff --git a/x.png b/x.png\nBinary files differ\n"))))))

(deftest what-a-remark-may-point-at
  (let [cache "src/main/java/acme/widget/SnapshotCache.java"
        renderer "src/main/java/acme/widget/Renderer.java"]
    (is (diff/lines-in? files {:file cache :side :new :line 52 :end-line 52}))
    (is (diff/lines-in? files {:file cache :side :new :line 41 :end-line 45}))
    (testing "removed lines, on the old side"
      (is (diff/lines-in? files {:file renderer :side :old :line 11 :end-line 11}))
      (is (not (diff/lines-in? files {:file renderer :side :new :line 11 :end-line 9}))))
    (testing "lines outside the diff, in the wrong order, or too many"
      (is (not (diff/lines-in? files {:file renderer :side :new :line 77 :end-line 77})))
      (is (not (diff/lines-in? files {:file cache :side :new :line 45 :end-line 41})))
      (is (not (diff/lines-in? files {:file cache :side :new :line 1 :end-line 58})))
      (is (not (diff/lines-in? files {:file "nope.java" :side :new :line 1 :end-line 1}))))))

(deftest the-lines-around-a-remark
  (let [cache "src/main/java/acme/widget/SnapshotCache.java"
        excerpt (diff/excerpt files {:file cache :side :new :line 52 :end-line 52} 2)]
    (is (= [50 51 52 53 54] (map :new excerpt)))
    (is (= [false false true false false] (map :marked excerpt))))
  (testing "within the hunk: none past its ends"
    (is (= [1 2 3] (map :new (diff/excerpt files {:file "src/main/java/acme/widget/SnapshotCache.java"
                                                   :side :new :line 1 :end-line 1} 2)))))
  (testing "a removed line, with the kept ones around it"
    (is (= [:ctx :del :add] (map :kind (diff/excerpt files {:file "src/main/java/acme/widget/Renderer.java"
                                                            :side :old :line 11 :end-line 11} 1)))))
  (testing "lines not in the diff have no excerpt"
    (is (nil? (diff/excerpt files {:file "x" :side :new :line 1 :end-line 1} 2)))))

(deftest a-line-is-found-by-its-text
  (is (= 52 (diff/locate-text files "src/main/java/acme/widget/SnapshotCache.java" :new
                              "if (!byKey.containsKey(key)) { byKey.put(key, load(key)); }")))
  (testing "a text found twice, or not at all, places nothing"
    (is (nil? (diff/locate-text files "src/main/java/acme/widget/SnapshotCache.java" :new "}")))
    (is (nil? (diff/locate-text files "src/main/java/acme/widget/SnapshotCache.java" :new "nope")))))
