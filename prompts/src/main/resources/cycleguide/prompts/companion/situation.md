{{!
  走行の相棒: いまの状況（ユーザープロンプトの前半）。後ろに companion/events/* のどれかが続く。
  変数:
    now           日時（例: 9月28日（月）14:05）
    time_of_day   朝・昼・夕方・夜・深夜
    season        春・夏・秋・冬
    style         走り方（ポタリング・ロングライドなど）
    weather       いまの天気（例: 霧雨、気温19℃）。取得できないときは null
    ride          今回の走行（開始のときは null）
      minutes       開始からの分数
      km            走った距離（km）
      spots         今回案内した場所（「、」区切り）。なければ null
    recent        今回の走行で最近話したこと（なければ null）
      lines         発言のリスト（古い順、最大4件）
}}
# いまの状況
- 日時: {{now}}（{{time_of_day}}、{{season}}）
- 走り方: {{style}}
{{#weather}}
- 天気: {{weather}}
{{/weather}}
{{#ride}}
- 今回の走行: 開始から{{minutes}}分、{{km}}km
{{#spots}}
- 今回案内した場所: {{spots}}
{{/spots}}
{{/ride}}
{{#recent}}

# 今回の走行であなたが最近話したこと（繰り返さないこと）
{{#lines}}
- {{.}}
{{/lines}}
{{/recent}}
