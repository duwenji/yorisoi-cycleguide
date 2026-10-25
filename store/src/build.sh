#!/bin/sh
# アイコンとフィーチャー グラフィックを src の SVG から作る。ImageMagick 7 と Windows の游ゴシックを使う。
set -e
cd "$(dirname "$0")"
magick -background none icon.svg PNG32:../icon-512.png
magick feature.svg -alpha off PNG24:feature-bg.png
magick feature-bg.png \( ../icon-512.png -resize 120x120 \) -geometry +64+110 -composite \
  -font C:/Windows/Fonts/YuGothB.ttc -fill "#1B3A1E" -pointsize 76 -annotate +205+202 "サイクルガイド" \
  -font C:/Windows/Fonts/YuGothM.ttc -fill "#2E4A30" -pointsize 29 -annotate +66+296 "走る先の見どころを、声で案内" \
  -pointsize 29 -annotate +66+342 "画面を見ずに、自転車を楽しめます" \
  -alpha off PNG24:../feature-1024x500.png
rm feature-bg.png
