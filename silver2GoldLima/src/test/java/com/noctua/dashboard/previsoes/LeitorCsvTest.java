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

import static org.junit.jupiter.api.Assertions.*;

class LeitorCsvTest {
    private static final String LOTE = "Timestamp,HostName,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n";
    private static final String HISTORICO = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n";
    private final LeitorCsv leitor = new LeitorCsv();
    private double[] valores(String[] linha) {
        return new double[]{Double.parseDouble(linha[3]), Double.parseDouble(linha[4]), Double.parseDouble(linha[5])};
    }

    @TempDir
    Path diretorio;

    private ArrayList<String[]> lerTexto(String csv) throws IOException {
        Path arquivo = diretorio.resolve("capturas.csv");
        Files.writeString(arquivo, csv);
        return leitor.ler(arquivo.toString(), "MF");
    }

    @Test
    void leArquivoUtf8ComNomeEHostnameAcentuados() throws IOException {
        Path arquivo = diretorio.resolve("histórico.csv");
        Files.writeString(arquivo, HISTORICO + "2026-11-11 14:37:00,MÁQUINA,2,40,50,60\n");

        ArrayList<String[]> capturas = leitor.ler(arquivo.toString(), "MÁQUINA");

        assertEquals(1, capturas.size());
        assertEquals(2, Integer.parseInt(capturas.get(0)[2]));
        assertArrayEquals(new double[]{40, 50, 60}, valores(capturas.get(0)), 0.0);
    }

    @Test
    void rejeitaArquivoVazio() {
        IOException erro = assertThrows(IOException.class, () -> lerTexto(""));

        assertEquals("Arquivo CSV sem cabeçalho.", erro.getMessage());
    }

    @Test
    void propagaErroAoAbrirArquivoAusente() {
        assertThrows(IOException.class, () -> leitor.ler(diretorio.resolve("ausente.csv").toString(), "MF"));
    }

    @Test
    void leLoteComColunasAdicionaisEmOutraOrdem() throws IOException {
        String csv = """
                Dados_Recebidos,Porcentagem_RAM,HostName,Timestamp,Porcentagem_Disco,Porcentagem_CPU,Freq_CPU
                1.5,50,MF,2026-11-11 14:37:45,60,0,3.2
                """;

        ArrayList<String[]> capturas = lerTexto(csv);

        assertEquals(1, capturas.size());
        assertEquals(LocalDateTime.of(2026, 11, 11, 14, 37), LocalDateTime.parse(capturas.get(0)[0], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertEquals(1, Integer.parseInt(capturas.get(0)[2]));
        assertArrayEquals(new double[]{0, 50, 60}, valores(capturas.get(0)), 0.0);
    }

    @Test
    void leHistoricoPreservandoQuantidadeEValoresSemJuntarAsLinhas() throws IOException {
        String csv = HISTORICO + """
                2026-11-11 14:37:00,MF,3,20,40,60
                2026-11-11 14:37:00,MF,1,80,60,100
                """;

        ArrayList<String[]> capturas = lerTexto(csv);

        assertEquals(2, capturas.size());
        assertEquals(3, Integer.parseInt(capturas.get(0)[2]));
        assertEquals(1, Integer.parseInt(capturas.get(1)[2]));
        assertArrayEquals(new double[]{20, 40, 60}, valores(capturas.get(0)), 0.0);
        assertArrayEquals(new double[]{80, 60, 100}, valores(capturas.get(1)), 0.0);

    }

    @Test
    void aceitaBomELinhasEmBranco() throws IOException {
        String csv = "\uFEFF" + HISTORICO.replace("\n", "\r\n")
                + "\r\n   \r\n2026-11-11 14:37:00,MF,2,40,50,60\r\n\r\n";

        ArrayList<String[]> capturas = lerTexto(csv);

        assertEquals(1, capturas.size());
        assertEquals(2, Integer.parseInt(capturas.get(0)[2]));
        assertArrayEquals(new double[]{40, 50, 60}, valores(capturas.get(0)), 0.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0,40,50,60", "-1,40,50,60", "1,NaN,50,60", "1,40,Infinity,60", "1,40,50,-Infinity"
    })
    void rejeitaQuantidadesOuPercentuaisInvalidos(String valores) {
        String csv = HISTORICO + "2026-11-11 14:37:00,MF," + valores + "\n";

        IOException erro = assertThrows(IOException.class,
                () -> lerTexto(csv));

        assertEquals("Captura com quantidade ou percentuais inválidos.", erro.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Timestamp,HostName,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n2026-11-11 14:37:00,OUTRO,40,50,60\n",
            "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n2026-11-11 14:37:00,OUTRO,2,40,50,60\n"
    })
    void rejeitaHostnameDiferenteNosDoisFormatos(String csv) {
        IOException erro = assertThrows(IOException.class,
                () -> lerTexto(csv));

        assertEquals("Hostname diferente dentro do histórico.", erro.getMessage());
    }

    @Test
    void rejeitaColunaObrigatoriaAusente() {
        String csv = "Timestamp,HostName,Porcentagem_RAM,Porcentagem_Disco\n2026-11-11 14:37:00,MF,50,60\n";

        IOException erro = assertThrows(IOException.class,
                () -> lerTexto(csv));

        assertEquals("Coluna não encontrada: Porcentagem_CPU", erro.getMessage());
    }
}
