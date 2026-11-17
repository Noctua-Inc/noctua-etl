package com.noctua.dashboard.previsoes;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtualizadorHistoricoCsvTest {
    private static final String LOTE = "Timestamp,HostName,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n";
    private static final String HISTORICO = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n";
    private final AtualizadorHistoricoCsv atualizador = new AtualizadorHistoricoCsv();
    private final LeitorCsv leitor = new LeitorCsv();
    private double[] valores(String[] linha) {
        return new double[]{Double.parseDouble(linha[3]), Double.parseDouble(linha[4]), Double.parseDouble(linha[5])};
    }

    @TempDir
    Path diretorio;

    @Test
    void criaPrimeiroHistoricoOrdenadoComMediasPorMinutoEZeros() throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        Path historico = diretorio.resolve("MF_historico.csv");
        String conteudo = LOTE + """
                2026-11-11 14:39:10,MF,0,0,0
                2026-11-11 14:37:50,MF,80,60,100
                2026-11-11 14:37:10,MF,20,40,60
                """;
        Files.writeString(lote, conteudo);

        atualizador.atualizar(lote.toString(), historico.toString(), "MF");

        ArrayList<String[]> capturas = leitor.ler(historico.toString(), "MF");
        assertEquals(2, capturas.size());
        assertEquals(LocalDateTime.of(2026, 11, 11, 14, 37), LocalDateTime.parse(capturas.get(0)[0], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertEquals(2, Integer.parseInt(capturas.get(0)[2]));
        assertArrayEquals(new double[]{50, 50, 80}, valores(capturas.get(0)), 0.0);
        assertEquals(LocalDateTime.of(2026, 11, 11, 14, 39), LocalDateTime.parse(capturas.get(1)[0], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertArrayEquals(new double[]{0, 0, 0}, valores(capturas.get(1)), 0.0);
        assertEquals(conteudo, Files.readString(lote));
    }

    @Test
    void combinaHistoricoComLotePreservandoPesoDasCapturas() throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(lote, LOTE + "2026-11-11 14:37:50,MF,80,60,100\n");
        Files.writeString(historico, HISTORICO + "2026-11-11 14:37:00,MF,3,20,40,60\n");

        atualizador.atualizar(lote.toString(), historico.toString(), "MF");

        ArrayList<String[]> capturas = leitor.ler(historico.toString(), "MF");
        assertEquals(1, capturas.size());
        assertEquals(4, Integer.parseInt(capturas.get(0)[2]));
        assertArrayEquals(new double[]{35, 45, 70}, valores(capturas.get(0)), 0.0);
        try (var arquivos = Files.list(diretorio)) {
            assertEquals(2, arquivos.count());
        }
    }

    @Test
    void loteAtrasadoPreservaCapturasNovasEJanelaDeSeteDias() throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(lote, LOTE + """
                2026-11-04 14:36:00,MF,10,20,30
                2026-11-04 14:37:00,MF,20,30,40
                2026-11-10 14:37:00,MF,30,40,50
                """);
        Files.writeString(historico, HISTORICO + "2026-11-11 14:37:00,MF,2,40,50,60\n");

        atualizador.atualizar(lote.toString(), historico.toString(), "MF");

        ArrayList<String[]> capturas = leitor.ler(historico.toString(), "MF");
        assertEquals(List.of(LocalDateTime.of(2026, 11, 4, 14, 37), LocalDateTime.of(2026, 11, 10, 14, 37),
                LocalDateTime.of(2026, 11, 11, 14, 37)), capturas.stream().map(linha -> LocalDateTime.parse(linha[0], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).toList());
        assertEquals(2, Integer.parseInt(capturas.get(2)[2]));
        assertArrayEquals(new double[]{40, 50, 60}, valores(capturas.get(2)), 0.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTRO,40,50,60", "MF,NaN,50,60", "MF,40,Infinity,60"})
    void loteInvalidoNaoModificaHistorico(String valores) throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        Path historico = diretorio.resolve("MF_historico.csv");
        String anterior = HISTORICO + "2026-11-11 14:37:00,MF,2,40,50,60\n";
        Files.writeString(lote, LOTE + "2026-11-11 14:38:00," + valores + "\n");
        Files.writeString(historico, anterior);

        assertThrows(IOException.class, () -> atualizador.atualizar(lote.toString(), historico.toString(), "MF"));

        assertEquals(anterior, Files.readString(historico));
    }

    @Test
    void loteSemCapturasPreservaHistoricoExistente() throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(lote, LOTE);
        Files.writeString(historico, HISTORICO + "2026-11-11 14:37:00,MF,2,40.0,50.0,60.0\n");
        List<String> anterior = Files.readAllLines(historico);

        atualizador.atualizar(lote.toString(), historico.toString(), "MF");

        assertEquals(anterior, Files.readAllLines(historico));
    }

    @Test
    void loteSemCapturasCriaSomenteCabecalhoNoPrimeiroHistorico() throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        Path historico = diretorio.resolve("MF_historico.csv");
        Files.writeString(lote, LOTE);

        atualizador.atualizar(lote.toString(), historico.toString(), "MF");

        assertEquals(List.of(HISTORICO.strip()), Files.readAllLines(historico));
    }

    @Test
    void rejeitaUsarOProprioLoteComoHistorico() throws IOException {
        Path lote = diretorio.resolve("lote.csv");
        String conteudo = LOTE + "2026-11-11 14:37:00,MF,40,50,60\n";
        Files.writeString(lote, conteudo);

        assertThrows(IOException.class, () -> atualizador.atualizar(lote.toString(), lote.toString(), "MF"));

        assertEquals(conteudo, Files.readString(lote));
    }
    @Test
    void loteEHistoricoEquivalentesProduzemMesmaSeriePreservandoZerosELacunas() throws IOException {
        String lote = LOTE + """
                2026-11-11 14:37:10,MF,20,40,60
                2026-11-11 14:37:50,MF,80,60,100
                2026-11-11 14:39:15,MF,0,0,0
                """;
        String historico = HISTORICO + """
                2026-11-11 14:37:00,MF,2,50,50,80
                2026-11-11 14:39:00,MF,1,0,0,0
                """;

        Path arquivoLote = diretorio.resolve("lote.csv");
        Path arquivoHistorico = diretorio.resolve("MF_historico.csv");
        Path esperado = diretorio.resolve("esperado.csv");
        Files.writeString(arquivoLote, lote);
        Files.writeString(esperado, historico);
        atualizador.atualizar(arquivoLote.toString(), arquivoHistorico.toString(), "MF");

        ArrayList<String[]> linhasAtualizadas = leitor.ler(arquivoHistorico.toString(), "MF");
        ArrayList<String[]> linhasEsperadas = leitor.ler(esperado.toString(), "MF");
        assertEquals(linhasEsperadas.size(), linhasAtualizadas.size());
        for (int i = 0; i < linhasEsperadas.size(); i++) {
            assertArrayEquals(linhasEsperadas.get(i), linhasAtualizadas.get(i));
        }
        CalculosPrevisoes calculos = new CalculosPrevisoes();
        CalculosPrevisoes referencia = new CalculosPrevisoes();
        calculos.lerHistorico(arquivoHistorico.toString(), "MF");
        referencia.lerHistorico(esperado.toString(), "MF");
        assertEquals(referencia.getAtual(), calculos.getAtual());
        assertEquals(referencia.getCapturasNoMinuto(), calculos.getCapturasNoMinuto());
        for (int m = 0; m < 3; m++) {
            assertEquals(referencia.calcular(m, 30.0, 80.0, referencia.getAtual().plusSeconds(10)),
                    calculos.calcular(m, 30.0, 80.0, calculos.getAtual().plusSeconds(10)));
        }
    }

}
