package com.noctua.processamento.previsoes;

import com.google.gson.JsonObject;

public class ArquivoJson {
    private final JsonObject dados;
    private final String etag;

    public ArquivoJson(JsonObject dados, String etag) {
        this.dados = dados;
        this.etag = etag;
    }

    public JsonObject getDados() {
        return dados;
    }

    public String getEtag() {
        return etag;
    }
}
