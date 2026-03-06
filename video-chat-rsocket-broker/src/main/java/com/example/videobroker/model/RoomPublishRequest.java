package com.example.videobroker.model;

public class RoomPublishRequest {

    private String action;
    private String route;
    private RoomEventMessage event;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public RoomEventMessage getEvent() {
        return event;
    }

    public void setEvent(RoomEventMessage event) {
        this.event = event;
    }
}
