{{!
  見どころの案内の依頼（ユーザープロンプト）。
  変数:
    name        スポット名
    category    種別（神社・寺院・史跡など）
    riding      走っているなら true（止まっているなら false）
    distance_m  利用者からの距離（m）。止まっているときに、通り過ぎた見どころを話すなら null
    passed      もう通り過ぎた見どころなら true
    tags        OpenStreetMap のタグ（"key=value" のリスト。案内に役立つものだけ）
}}
自転車で走っている利用者に、見どころを案内してください。
名称: {{name}}
種別: {{category}}
{{#riding}}
利用者の状態: 走っている。この見どころは進む先、約{{distance_m}}mにある
{{/riding}}
{{^riding}}
{{#passed}}
利用者の状態: 止まっている。この見どころは、さきほど通り過ぎた
通り過ぎたことを謝ったり残念がったりせず、どんな場所だったかを短く伝える
{{/passed}}
{{^passed}}
利用者の状態: 止まっている
{{/passed}}
{{/riding}}
OpenStreetMapのタグ:
{{#tags}}
- {{.}}
{{/tags}}
