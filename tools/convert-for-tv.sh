#!/bin/sh
# Convert videos to a format old Fire TV sticks can play:
# H.264, at most 1080p, 30 fps, normal (SDR) colours, AAC audio.
#
#   tools/convert-for-tv.sh clip1.mov clip2.mp4 ...   ->  clip1.tv.mp4 clip2.tv.mp4
#
# Needs ffmpeg (Arch: sudo pacman -S ffmpeg). Then push with adb or the laptop script.
set -e
for in in "$@"; do
    out="${in%.*}.tv.mp4"
    ffmpeg -hide_banner -y -i "$in" \
        -vf "scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,fps=30,format=yuv420p" \
        -c:v libx264 -profile:v high -level 4.1 -preset medium -crf 22 -maxrate 8M -bufsize 16M \
        -c:a aac -b:a 128k -movflags +faststart \
        "$out"
    echo "-> $out"
done
