package com.noctua.dashboard.previsoes;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CalculosPrevisoesTest {
    private static final String CABECALHO = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n";
    @TempDir
    Path diretorio;

    @Test
    void releHistoricoAposAtualizacaoDasMediasNoCsv() throws IOException {
        Path historico = diretorio.resolve("MF_historico.csv");
        Path lote = diretorio.resolve("lote.csv");
        Files.writeString(historico, CABECALHO + "2026-11-11 14:37:00,MF,3,20,40,60\n");
        CalculosPrevisoes calculos = new CalculosPrevisoes();
        calculos.lerHistorico(historico.toString(), "MF");
        assertEquals(20, atual(calculos).getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());

        Files.writeString(lote, "Timestamp,HostName,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n"
                + "2026-11-11 14:37:50,MF,80,60,100\n");
        new AtualizadorHistoricoCsv().atualizar(lote.toString(), historico.toString(), "MF");
        calculos.lerHistorico(historico.toString(), "MF");

        assertEquals(4, calculos.getCapturasNoMinuto());
        assertEquals(35, atual(calculos).getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());

        Files.writeString(lote, "Timestamp,HostName,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n"
                + "2026-11-11 14:39:00,MF,0,0,0\n");
        new AtualizadorHistoricoCsv().atualizar(lote.toString(), historico.toString(), "MF");
        calculos.lerHistorico(historico.toString(), "MF");
        assertEquals(LocalDateTime.of(2026, 11, 11, 14, 39), calculos.getAtual());
        assertEquals(1, calculos.getCapturasNoMinuto());
        assertEquals(0, atual(calculos).getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());
    }

    @Test
    void usaTimestampsParaDistinguirZeroDeHoraSemCapturas() throws IOException {
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(historico, CABECALHO + "2026-11-11 14:37:00,MF,3,0,0,0\n"
                + "2026-11-11 16:37:00,MF,2,55,50,60\n");
        CalculosPrevisoes calculos = new CalculosPrevisoes();
        calculos.lerHistorico(historico.toString(), "MF");
        JsonObject graficos = atual(calculos).getAsJsonObject("graficos");

        assertEquals(55, graficos.getAsJsonObject("atual").get("valor").getAsDouble());
        JsonObject horaComZero = graficos.getAsJsonArray("historico").get(4).getAsJsonObject();
        JsonObject horaSemCapturas = graficos.getAsJsonArray("historico").get(5).getAsJsonObject();
        assertEquals("2026-11-11T14:00-03:00", horaComZero.get("horario").getAsString());
        assertEquals(0, horaComZero.get("valor").getAsDouble());
        assertEquals("2026-11-11T15:00-03:00", horaSemCapturas.get("horario").getAsString());
        assertTrue(horaSemCapturas.get("valor").isJsonNull());
    }

    private JsonObject atual(CalculosPrevisoes calculos) {
        return calculos.calcular(0, 30.0, 80.0, calculos.getAtual().plusSeconds(10));
    }
}
