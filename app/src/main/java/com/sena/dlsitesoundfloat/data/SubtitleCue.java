package com.sena.dlsitesoundfloat.data;

import java.util.List;

public class SubtitleCue {
    public double startTime;
    public double endTime;
    public List<String> subtitles;

    public SubtitleCue(double startTime, double endTime, List<String> subtitles) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.subtitles = subtitles;
    }
}
