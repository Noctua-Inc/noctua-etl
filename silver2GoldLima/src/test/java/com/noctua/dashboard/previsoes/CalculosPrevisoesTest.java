package com.noctua.dashboard.previsoes;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class CalculosPrevisoesTest {
    private static final String CABECALHO = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco,Leitura_Disco_MiB_s,Escrita_Disco_MiB_s\n";
    @TempDir
    Path diretorio;

    @Test
    void releHistoricoAposAtualizacaoDasMediasNoCsv() throws IOException {
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(historico, CABECALHO + "2026-11-11 14:37:00,MF,3,20,40,60,120.0,240.0\n");
        CalculosPrevisoes calculos = new CalculosPrevisoes();
        calculos.lerHistorico(historico.toString(), "MF");
        assertEquals(20, atual(calculos).getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());

        Files.writeString(historico, CABECALHO + "2026-11-11 14:37:00,MF,4,35,45,70,120.0,240.0\n");
        calculos.lerHistorico(historico.toString(), "MF");

        assertEquals(4, calculos.getCapturasNoMinuto());
        assertEquals(35, atual(calculos).getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());

        Files.writeString(historico, CABECALHO + "2026-11-11 14:37:00,MF,4,35,45,70,120.0,240.0\n"
                + "2026-11-11 14:39:00,MF,1,0,0,0,120.0,240.0\n");
        calculos.lerHistorico(historico.toString(), "MF");
        assertEquals(LocalDateTime.of(2026, 11, 11, 14, 39), calculos.getAtual());
        assertEquals(1, calculos.getCapturasNoMinuto());
        assertEquals(0, atual(calculos).getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());
    }

    @Test
    void usaTimestampsParaDistinguirZeroDeHoraSemCapturas() throws IOException {
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(historico, CABECALHO + "2026-11-11 14:37:00,MF,3,0,0,0,120.0,240.0\n"
                + "2026-11-11 16:37:00,MF,2,55,50,60,120.0,240.0\n");
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

    @Test
    void selecionaPersistenciaQuandoOcupacaoDoDiscoEstaEstavel() throws IOException {
        DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder csv = new StringBuilder(CABECALHO);
        LocalDateTime inicio = LocalDateTime.of(2026, 11, 4, 14, 0);
        for (int i = 0; i <= 7 * 1440; i++) {
            int ocupacao = i < 4 * 1440 ? 45 : 60;
            csv.append(inicio.plusMinutes(i).format(formato)).append(",MF,1,20,50,")
                    .append(ocupacao).append(",120,240\n");
        }
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(historico, csv);
        CalculosPrevisoes calculos = new CalculosPrevisoes();
        calculos.lerHistorico(historico.toString(), "MF");
        JsonObject resultado = calculos.calcular(2, 30.0, 80.0, calculos.getAtual().plusSeconds(10));
        assertEquals("Persistência", resultado.get("modelo").getAsString());
        var previsao = resultado.getAsJsonObject("graficos").getAsJsonArray("previsao");
        assertEquals(36, previsao.size());
        for (var ponto : previsao) {
            assertEquals(60, ponto.getAsJsonObject().get("valor").getAsDouble());
        }
    }
}
