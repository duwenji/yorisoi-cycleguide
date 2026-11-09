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
    nearby        周り（約1km以内）の、まだ話していない見どころ（platform REQ-NEARBY-TALK F-9）。休憩・区切りのときだけ。ないときは null
      spots         各要素は name（名称）・category（種別）・distance_m（距離。50m単位）・compass（8方位）・
                    side（進む向きから見て 前方・右手・左手・後ろ。分からないときは null）
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
{{#nearby}}

# 周りの見どころ（約1km以内、まだ話していないもの）
{{#spots}}
- {{name}}（{{category}}）: 約{{distance_m}}m、{{compass}}{{#side}}・{{side}}{{/side}}
{{/spots}}
{{/nearby}}
{{#recent}}

# 今回の走行であなたが最近話したこと（繰り返さないこと）
{{#lines}}
- {{.}}
{{/lines}}
{{/recent}}
