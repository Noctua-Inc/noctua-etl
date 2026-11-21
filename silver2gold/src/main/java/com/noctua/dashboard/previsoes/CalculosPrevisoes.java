package com.noctua.dashboard.previsoes;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.time.*;
import java.util.*;

public class CalculosPrevisoes {
    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    public static final String[] NOMES = {"cpu", "ram", "ocupacao", "leitura", "escrita"};
    private static final int MINUTOS_POR_DIA = 1440;
    private static final int DIAS_DO_PERFIL = 28;

    private final LeitorCsv leitor = new LeitorCsv();
    private final DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private LocalDateTime inicio;
    private LocalDateTime atual;
    private double[][] minutos;
    private int capturasNoMinuto;

    public void lerHistorico(String caminho, String hostname) throws IOException {
        carregarHistorico(leitor.ler(caminho, hostname));
    }

    public void lerHistorico(InputStream entrada, String hostname) throws IOException {
        carregarHistorico(leitor.ler(entrada, hostname));
    }

    private void carregarHistorico(ArrayList<String[]> historico) {
        if (historico.isEmpty()) throw new IllegalArgumentException("Histórico sem capturas.");
        String[] ultimaLinha = historico.get(historico.size() - 1);
        atual = LocalDateTime.parse(ultimaLinha[0], formato);
        capturasNoMinuto = Integer.parseInt(ultimaLinha[2]);
        inicio = atual.minusDays(DIAS_DO_PERFIL);
        LocalDateTime primeiraCaptura = LocalDateTime.parse(historico.get(0)[0], formato);
        if (primeiraCaptura.isAfter(inicio)) inicio = primeiraCaptura;
        minutos = new double[indice(atual) + 1][];
        for (String[] linha : historico) {
            LocalDateTime horario = LocalDateTime.parse(linha[0], formato);
            int i = indice(horario);
            if (i >= 0 && i < minutos.length) {
                minutos[i] = new double[]{
                        Double.parseDouble(linha[3]),
                        Double.parseDouble(linha[4]),
                        Double.parseDouble(linha[5]),
                        Double.parseDouble(linha[6]),
                        Double.parseDouble(linha[7])
                };
            }
        }
    }

    public LocalDateTime getAtual() {
        return atual;
    }

    public int getCapturasNoMinuto() {
        return capturasNoMinuto;
    }

    private int indice(LocalDateTime horario) {
        return (int) Duration.between(inicio, horario).toMinutes();
    }

    private double valor(int metrica, int indice) {
        if (indice < 0 || indice >= minutos.length || minutos[indice] == null) return Double.NaN;
        return minutos[indice][metrica];
    }

    public static String horario(LocalDateTime data) {
        return data.atZone(FUSO).toOffsetDateTime().toString();
    }

    public static LocalDateTime data(String valor) {
        return OffsetDateTime.parse(valor).atZoneSameInstant(FUSO).toLocalDateTime();
    }

    public static LocalDateTime inicioGrafico(LocalDateTime atual) {
        return atual.withMinute(0).minusHours(6);
    }

    public static LocalDateTime fimGrafico(LocalDateTime atual) {
        return atual.withMinute(0).plusHours(6);
    }

    public static JsonObject ponto(LocalDateTime horario, Double valor) {
        JsonObject ponto = new JsonObject();
        ponto.addProperty("horario", horario(horario));
        numero(ponto, "valor", valor);
        return ponto;
    }

    public static void numero(JsonObject objeto, String nome, Double valor) {
        if (valor == null || !Double.isFinite(valor)) objeto.add(nome, JsonNull.INSTANCE);
        else objeto.addProperty(nome, Math.round(valor * 10000) / 10000.0);
    }

    private static double limitar(double valor) {
        return Math.max(0, Math.min(100, valor));
    }

    private int tipoDeDia(int minuto) {
        return inicio.plusMinutes(minuto).getDayOfWeek().getValue() >= 6 ? 1 : 0;
    }

    private static double mediana(double[] valores, int quantidade) {
        if (quantidade == 0) return Double.NaN;
        Arrays.sort(valores, 0, quantidade);
        return (valores[quantidade / 2] + valores[(quantidade - 1) / 2]) / 2;
    }

    private class PerfilSazonal {
        private final int metrica;
        private final double[] todos = new double[DIAS_DO_PERFIL];
        private final double[] semelhantes = new double[DIAS_DO_PERFIL];
        private final double[] mesmoDiaDaSemana = new double[DIAS_DO_PERFIL / 7];
        private final double[] vizinhanca = new double[21];

        PerfilSazonal(int metrica) {
            this.metrica = metrica;
        }

        double calcular(int origem, int alvo) {
            DayOfWeek diaDoAlvo = inicio.plusMinutes(alvo).getDayOfWeek();
            boolean fimDeSemana = diaDoAlvo.getValue() >= 6;
            int total = 0, doMesmoTipo = 0, doMesmoDia = 0;
            for (int dia = 1; dia <= DIAS_DO_PERFIL; dia++) {
                int centro = alvo - dia * MINUTOS_POR_DIA;
                if (centro < 0) break;
                int quantidade = 0;
                for (int i = Math.max(0, centro - 10); i <= Math.min(origem, centro + 10); i++) {
                    double medicao = valor(metrica, i);
                    if (Double.isFinite(medicao)) vizinhanca[quantidade++] = medicao;
                }
                if (quantidade == 0) continue;
                double referencia = mediana(vizinhanca, quantidade);
                todos[total++] = referencia;
                boolean fimDeSemanaAnterior = diaDoAlvo.minus(dia).getValue() >= 6;
                if (fimDeSemanaAnterior == fimDeSemana) semelhantes[doMesmoTipo++] = referencia;
                if (dia % 7 == 0) mesmoDiaDaSemana[doMesmoDia++] = referencia;
            }
            if (doMesmoDia >= 2) return mediana(mesmoDiaDaSemana, doMesmoDia);
            if (doMesmoTipo > 0) return mediana(semelhantes, doMesmoTipo);
            return mediana(todos, total);
        }
    }

    private record AnaliseSazonal(double desvio, double inclinacao) {}

    private AnaliseSazonal analisarDesvios(int metrica, PerfilSazonal perfil) {
        int ultimo = indice(atual);
        int janela = metrica == 0 ? 30 : 60;
        int inicioGrafico = indice(inicioGrafico(atual));
        int primeiroDesvio = Math.max(0, inicioGrafico - 2);
        int primeiroDia = indice(inicio.plusMinutes(primeiroDesvio).toLocalDate().atStartOfDay());
        int primeiroResiduo = Math.max(0, primeiroDia - 7 * MINUTOS_POR_DIA);
        double[] residuos = new double[ultimo - primeiroResiduo + 1];
        for (int i = primeiroResiduo; i <= ultimo; i++) {
            double observado = valor(metrica, i);
            residuos[i - primeiroResiduo] = Double.isFinite(observado)
                    ? observado - perfil.calcular(i - 1, i)
                    : Double.NaN;
        }

        int sequencia = 0;
        int diaCalibrado = Integer.MIN_VALUE;
        double limite = Double.NaN;
        for (int i = primeiroDesvio; i <= ultimo; i++) {
            int dia = indice(inicio.plusMinutes(i).toLocalDate().atStartOfDay());
            if (dia != diaCalibrado) {
                limite = calibrarFaixa(residuos, primeiroResiduo, dia, tipoDeDia(i));
                diaCalibrado = dia;
            }
            double residuo = residuos[i - primeiroResiduo];
            int direcao = 0;
            if (Math.abs(residuo) > limite) direcao = residuo > 0 ? 1 : -1;
            sequencia = Integer.signum(sequencia) == direcao ? sequencia + direcao : direcao;
        }
        if (Math.abs(sequencia) < 3) return new AnaliseSazonal(0, 0);
        return new AnaliseSazonal(residuos[residuos.length - 1], inclinacaoRecente(residuos, janela));
    }

    private double calibrarFaixa(double[] residuos, int primeiro, int dia, int tipo) {
        // Calibra somente com minutos dos dias anteriores ao dia analisado.
        double[] erros = new double[7 * MINUTOS_POR_DIA];
        int quantidade = 0;
        for (int i = dia - erros.length; i < dia; i += MINUTOS_POR_DIA) {
            if (tipoDeDia(i) != tipo) continue;
            for (int minuto = Math.max(primeiro, i); minuto < i + MINUTOS_POR_DIA; minuto++) {
                double residuo = residuos[minuto - primeiro];
                if (Double.isFinite(residuo)) erros[quantidade++] = Math.abs(residuo);
            }
        }
        if (quantidade < 60) return Double.NaN;
        Arrays.sort(erros, 0, quantidade);
        return erros[(int) Math.ceil(0.995 * quantidade) - 1];
    }

    private double inclinacaoRecente(double[] residuos, int janela) {
        double somaX = 0, somaY = 0, somaXX = 0, somaXY = 0;
        int quantidade = 0;
        for (int i = Math.max(0, residuos.length - janela); i < residuos.length; i++) {
            double y = residuos[i];
            if (!Double.isFinite(y)) continue;
            double x = i - residuos.length + 1;
            somaX += x;
            somaY += y;
            somaXX += x * x;
            somaXY += x * y;
            quantidade++;
        }
        double denominador = quantidade * somaXX - somaX * somaX;
        if (quantidade < janela / 2 || denominador == 0) return 0;
        return (quantidade * somaXY - somaX * somaY) / denominador;
    }

    public JsonObject calcular(int metrica, LocalDateTime gerado) {
        int origem = indice(atual);
        PerfilSazonal perfil = new PerfilSazonal(metrica);
        AnaliseSazonal analise = analisarDesvios(metrica, perfil);
        boolean usouPerfil = false;
        JsonObject resultado = new JsonObject();
        resultado.addProperty("unidade", metrica < 3 ? "%" : "MiB/s");
        JsonObject graficos = new JsonObject();
        JsonArray historico = new JsonArray();
        for (LocalDateTime hora = inicioGrafico(atual); !hora.isAfter(atual); hora = hora.plusHours(1)) {
            historico.add(ponto(hora, media(metrica, hora, hora.plusHours(1))));
        }
        graficos.add("historico", historico);
        graficos.add("atual", ponto(atual, valor(metrica, indice(atual))));
        JsonArray previsao = new JsonArray();
        LocalDateTime futuro = atual.plusMinutes(10 - atual.getMinute() % 10);
        for (; !futuro.isAfter(fimGrafico(atual)); futuro = futuro.plusMinutes(10)) {

            if (!futuro.isAfter(gerado)) continue;
            int alvo = indice(futuro);
            double valor = perfil.calcular(origem, alvo);
            if (Double.isFinite(valor)) {
                usouPerfil = true;
                int distancia = alvo - origem;
                double peso = distancia <= 10 ? 1 : Math.exp(-(distancia - 10) / 20.0);
                valor += peso * (analise.desvio() + analise.inclinacao() * Math.min(distancia, 30));
            } else {
                valor = valor(metrica, origem);
            }
            JsonObject ponto = ponto(futuro, metrica < 3 ? limitar(valor) : Math.max(0, valor));
            previsao.add(ponto);
        }
        graficos.add("previsao", previsao);
        String modelo = "Persistência";
        if (usouPerfil) {
            modelo = analise.desvio() == 0 ? "Perfil diário" : "Perfil diário com regressão linear recente";
        }
        resultado.addProperty("modelo", modelo);
        resultado.add("graficos", graficos);
        return resultado;
    }

    private Double media(int metrica, LocalDateTime inicio, LocalDateTime fim) {
        double soma = 0;
        int quantidade = 0;
        for (int i = Math.max(0, indice(inicio)); i < Math.min(minutos.length, indice(fim)); i++) {
            double valor = valor(metrica, i);
            if (Double.isFinite(valor)) { soma += valor; quantidade++; }
        }
        if (quantidade == 0) {
            return null;
        } else {
            return soma / quantidade;
        }
    }
}
