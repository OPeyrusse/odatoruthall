(ns odato.ui.markdown-test
  "What a review writes is shown as markdown, and never as markup: it quotes
  pull request descriptions, which anyone who opens a pull request writes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hiccup2.core :as h]
            [odato.ui.markdown :as markdown]))

(defn- html [text] (str (h/html (markdown/render text))))

(deftest what-a-review-writes-is-understood
  (let [out (html "### Verdict\n\nSome **bold**, *italic* and `code`.\n\n- one\n- two\n\n1. first\n\n```\nlet x = 1;\n```")]
    (is (str/includes? out "<strong>bold</strong>"))
    (is (str/includes? out "<em>italic</em>"))
    (is (str/includes? out "code</code>"))
    (is (str/includes? out "<ul class=\"list-disc pl-5\"><li>one</li><li>two</li></ul>"))
    (is (str/includes? out "<ol"))
    (is (str/includes? out "let x = 1;</pre>"))))

(deftest nothing-becomes-markup
  (let [out (html "<script>alert(1)</script>\n\n[click](javascript:alert(1)) and [ok](https://example.com)")]
    (is (not (str/includes? out "<script>")))
    (is (str/includes? out "&lt;script&gt;"))
    (is (not (str/includes? out "javascript:alert(1)\"")) "a link that is not http is kept as text")
    (is (str/includes? out "href=\"https://example.com\""))))
