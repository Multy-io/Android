/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.model.entities;

import com.google.gson.annotations.SerializedName;

/**
 * Created by anschutz1927@gmail.com on 31.07.18.
 */
public class EosAccountPriceRequest {

    @SerializedName("ram")
    private int ram;
    @SerializedName("cpu")
    private double cpu;
    @SerializedName("net")
    private double net;

    public EosAccountPriceRequest() { }

    public EosAccountPriceRequest(int ram, double cpu, double net) {
        this.ram = ram;
        this.cpu = cpu;
        this.net = net;
    }

    public int getRam() {
        return ram;
    }

    public void setRam(int ram) {
        this.ram = ram;
    }

    public double getCpu() {
        return cpu;
    }

    public void setCpu(double cpu) {
        this.cpu = cpu;
    }

    public double getNet() {
        return net;
    }

    public void setNet(double net) {
        this.net = net;
    }
}
