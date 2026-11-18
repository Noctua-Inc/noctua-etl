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

    private double mediana(ArrayList<Double> valores) {
        if (valores.isEmpty()) return Double.NaN;
        Collections.sort(valores);
        int meio = valores.size() / 2;
        if (valores.size() % 2 == 1) return valores.get(meio);
        return (valores.get(meio - 1) + valores.get(meio)) / 2;
    }

    private int primeiroMinutoDaSemana() {
        return Math.max(0, minutos.length - 1 - 7 * MINUTOS_POR_DIA);
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

    private record AnaliseSazonal(JsonArray anomalias, double desvio, double inclinacao) {}

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

        JsonArray anomalias = new JsonArray();
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
            if (i >= inicioGrafico && Math.abs(sequencia) >= 3) {
                double observado = valor(metrica, i);
                JsonObject ponto = ponto(inicio.plusMinutes(i), observado);
                numero(ponto, "referencia", observado - residuo);
                numero(ponto, "score", residuo / Math.max(limite, 0.0001));
                anomalias.add(ponto);
            }
        }
        if (Math.abs(sequencia) < 3) return new AnaliseSazonal(anomalias, 0, 0);
        return new AnaliseSazonal(anomalias, residuos[residuos.length - 1], inclinacaoRecente(residuos, janela));
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

    public JsonObject calcular(int metrica, Double minimo, Double maximo, LocalDateTime gerado) {
        int origem = indice(atual);
        PerfilSazonal perfil = new PerfilSazonal(metrica);
        AnaliseSazonal analise = analisarDesvios(metrica, perfil);
        boolean usouPerfil = false;
        JsonObject resultado = new JsonObject();
        resultado.addProperty("unidade", metrica < 3 ? "%" : "MiB/s");
        numero(resultado, "limite_minimo", minimo);
        numero(resultado, "limite_maximo", maximo);
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
        graficos.add("mapa", mapa(metrica));
        String modelo = "Persistência";
        if (usouPerfil) {
            modelo = analise.desvio() == 0 ? "Perfil diário" : "Perfil diário com regressão linear recente";
        }
        resultado.addProperty("modelo", modelo);
        resultado.add("graficos", graficos);
        resultado.add("indicadores", indicadores(metrica, previsao, minimo, maximo, gerado));
        resultado.add("analise", diagnostico(metrica, analise));
        return resultado;
    }

    private JsonArray anomaliasDaOcupacao() {
        int metrica = 2;
        JsonArray anomalias = new JsonArray();
        int ultimo = indice(atual);
        for (int i = Math.max(0, indice(inicioGrafico(atual))); i <= ultimo; i++) {
            double observado = valor(metrica, i);
            if (!Double.isFinite(observado)) continue;
            ArrayList<Double> dias = new ArrayList<>();
            for (int dia = 1; dia <= 7; dia++) {
                ArrayList<Double> valores = new ArrayList<>();
                for (int j = i - dia * 1440 - 10; j <= i - dia * 1440 + 10; j++) {
                    double valor = valor(metrica, j);
                    if (j >= primeiroMinutoDaSemana() && Double.isFinite(valor)) valores.add(valor);
                }
                if (!valores.isEmpty()) dias.add(mediana(valores));
            }
            if (dias.size() < 3) continue;
            double centro = mediana(dias);
            ArrayList<Double> distancias = new ArrayList<>();
            for (double valor : dias) distancias.add(Math.abs(valor - centro));
            double mad = mediana(distancias);
            // O piso de 1 p.p. permite analisar séries praticamente constantes.
            double dispersao = Math.max(1, mad / 0.6745);
            double score = (observado - centro) / dispersao;
            if (Math.abs(score) > 3.5) {
                JsonObject ponto = ponto(inicio.plusMinutes(i), observado);
                numero(ponto, "referencia", centro);
                numero(ponto, "score", score);
                anomalias.add(ponto);
            }
        }
        return anomalias;
    }

    private JsonObject diagnostico(int metrica, AnaliseSazonal analise) {
        JsonObject resultado = new JsonObject();
        resultado.add("anomalias_observadas", metrica == 2 ? anomaliasDaOcupacao() : analise.anomalias());
        resultado.addProperty("tipo_anomalia", metrica == 2
                ? "Desvio das médias de minuto frente ao mesmo horário de dias anteriores"
                : "Três ou mais minutos seguidos fora da faixa histórica para dias semelhantes");
        JsonArray correlacoes = new JsonArray();
        for (int outra = 0; outra < NOMES.length; outra++) {
            if (outra == metrica) continue;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int n = 0;
            for (int i = primeiroMinutoDaSemana(); i < minutos.length; i++) {
                double x = valor(metrica, i), y = valor(outra, i);
                if (Double.isFinite(x) && Double.isFinite(y)) {
                    sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; n++;
                }
            }
            double denominador = Math.sqrt(Math.max(0, n * sxx - sx * sx) * Math.max(0, n * syy - sy * sy));
            JsonObject item = new JsonObject();
            item.addProperty("componente", NOMES[outra]);
            Double pearson;
            if (n < 30 || denominador == 0) {
                pearson = null;
            } else {
                pearson = Math.max(-1, Math.min(1, (n * sxy - sx * sy) / denominador));
            }
            numero(item, "pearson", pearson);
            item.addProperty("pares", n);
            correlacoes.add(item);
        }
        resultado.add("correlacoes", correlacoes);
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

    private JsonArray mapa(int metrica) {
        JsonArray mapa = new JsonArray();
        LocalDate primeiro = atual.toLocalDate().minusDays(6);
        for (int dia = 0; dia < 7; dia++) {
            LocalDate data = primeiro.plusDays(dia);
            for (int hora = 0; hora < 24; hora++) {
                Double valor = media(metrica, data.atTime(hora, 0), data.atTime(hora, 0).plusHours(1));
                JsonObject ponto = new JsonObject();
                ponto.addProperty("dia", data.toString());
                ponto.addProperty("hora", hora);
                numero(ponto, "media", valor);
                Double intensidade;
                if (valor == null) {
                    intensidade = null;
                } else {
                    intensidade = limitar(valor) / 100;
                }
                numero(ponto, "intensidade", intensidade);
                mapa.add(ponto);
            }
        }
        return mapa;
    }

    private JsonObject indicadores(int metrica, JsonArray pontos, Double minimo, Double maximo, LocalDateTime gerado) {
        JsonObject indicadores = new JsonObject();
        Double maior = null, menor = null;
        Integer minutos = null;
        String tipo = null;
        double atual = valor(metrica, indice(this.atual));
        if (minimo != null && atual < minimo) { minutos = 0; tipo = "abaixo"; }
        else if (maximo != null && atual > maximo) { minutos = 0; tipo = "acima"; }
        int minutoAnterior = 0;
        double valorAnterior = atual;
        for (JsonElement elemento : pontos) {
            JsonObject ponto = elemento.getAsJsonObject();
            if (ponto.get("valor").isJsonNull()) continue;
            double valor = ponto.get("valor").getAsDouble();
            int minuto = indice(data(ponto.get("horario").getAsString())) - indice(this.atual);
            if (maior == null) {
                maior = valor;
            } else {
                maior = Math.max(maior, valor);
            }
            if (menor == null) {
                menor = valor;
            } else {
                menor = Math.min(menor, valor);
            }
            if (minutos == null && ((minimo != null && valor < minimo) || (maximo != null && valor > maximo))) {
                boolean abaixo = minimo != null && valor < minimo;
                tipo = abaixo ? "abaixo" : "acima";
                minutos = minuto;
                if (Double.isFinite(valorAnterior) && minuto - minutoAnterior <= 10) {
                    double limite = abaixo ? minimo : maximo;
                    double cruzamento = minutoAnterior
                            + (limite - valorAnterior) / (valor - valorAnterior) * (minuto - minutoAnterior);
                    int primeiroFuturo = Math.max(1, indice(gerado) - indice(this.atual) + 1);
                    minutos = Math.min(minuto, Math.max(primeiroFuturo, (int) Math.floor(cruzamento + 1e-9) + 1));
                }
            }
            minutoAnterior = minuto;
            valorAnterior = valor;
        }
        numero(indicadores, "maior_previsto", maior);
        numero(indicadores, "menor_previsto", menor);
        Double minutosAteSaida;
        if (minutos == null) {
            minutosAteSaida = null;
        } else {
            minutosAteSaida = minutos.doubleValue();
        }
        numero(indicadores, "primeira_saida_em_minutos", minutosAteSaida);
        indicadores.addProperty("primeira_saida_tipo", tipo);
        return indicadores;
    }
}
