package com.noctua.model;

import java.util.List;

public class Mainframe {
    private final int idMainframe;
    private final String hostname;
    private final List<Limite> limites;

    public Mainframe(int idMainframe, String hostname, List<Limite> limites) {
        this.idMainframe = idMainframe;
        this.hostname = hostname;
        this.limites = limites;
    }

    public int getIdMainframe() {
        return idMainframe;
    }

    public String getHostname() {
        return hostname;
    }

    public List<Limite> getLimites() {
        return limites;
    }
}
