package com.example.demo;

public class SpeedResult {
    private String downloadSpeed;
    private String uploadSpeed;
    private String latency;
    private boolean isCompleted;

    public SpeedResult(String downloadSpeed, String uploadSpeed, String latency, boolean isCompleted) {
        this.downloadSpeed = downloadSpeed;
        this.uploadSpeed = uploadSpeed;
        this.latency = latency;
        this.isCompleted = isCompleted;
    }

    public String getDownloadSpeed() { return downloadSpeed; }
    public String getUploadSpeed() { return uploadSpeed; }
    public String getLatency() { return latency; }
    public boolean isCompleted() { return isCompleted; }

    public void setDownloadSpeed(String downloadSpeed) { this.downloadSpeed = downloadSpeed; }
    public void setUploadSpeed(String uploadSpeed) { this.uploadSpeed = uploadSpeed; }
    public void setLatency(String latency) { this.latency = latency; }
    public void setCompleted(boolean completed) { isCompleted = completed; }
}