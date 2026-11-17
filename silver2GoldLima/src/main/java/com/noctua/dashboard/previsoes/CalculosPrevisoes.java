package com.noctua.dashboard.previsoes;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.time.*;
import java.util.*;

public class CalculosPrevisoes {
    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    public static final String[] NOMES = {"cpu", "ram", "ocupacao"};

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
        inicio = atual.minusDays(7);
        minutos = new double[7 * 1440 + 1][];
        for (String[] linha : historico) {
            LocalDateTime horario = LocalDateTime.parse(linha[0], formato);
            int i = indice(horario);
            if (i >= 0 && i < minutos.length) {
                minutos[i] = new double[]{
                        Double.parseDouble(linha[3]),
                        Double.parseDouble(linha[4]),
                        Double.parseDouble(linha[5])
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

    private double perfil(int metrica, int origem, int alvo) {
        ArrayList<Double> dias = new ArrayList<>();
        // Cada dia contribui com uma mediana, mesmo se tiver mais capturas que outro.
        for (int dia = 1; dia <= 7; dia++) {
            int centro = alvo - dia * 1440;
            ArrayList<Double> vizinhanca = new ArrayList<>();
            for (int i = centro - 10; i <= centro + 10; i++) {
                if (i <= origem && i < alvo && i >= 0) {
                    double valor = valor(metrica, i);
                    if (Double.isFinite(valor)) vizinhanca.add(valor);
                }
            }
            if (!vizinhanca.isEmpty()) dias.add(mediana(vizinhanca));
        }
        return mediana(dias);
    }

    private double prever(int metrica, int origem, int alvo, int modelo) {
        double atual = valor(metrica, origem);
        if (modelo == 0) return atual;
        double base = perfil(metrica, origem, alvo);
        if (!Double.isFinite(base)) return Double.NaN;
        if (modelo == 1) return base;

        double somaX = 0, somaY = 0, somaXX = 0, somaXY = 0;
        int quantidade = 0;
        for (int i = Math.max(0, origem - 59); i <= origem; i++) {
            double observado = valor(metrica, i);
            double esperado = perfil(metrica, i - 1, i);
            if (Double.isFinite(observado) && Double.isFinite(esperado)) {
                double x = i - origem;
                double y = observado - esperado;
                somaX += x;
                somaY += y;
                somaXX += x * x;
                somaXY += x * y;
                quantidade++;
            }
        }
        double denominador = quantidade * somaXX - somaX * somaX;
        if (quantidade < 30 || denominador == 0) return Double.NaN;
        double inclinacao = (quantidade * somaXY - somaX * somaY) / denominador;
        double desvioAtual = (somaY - inclinacao * somaX) / quantidade;
        int distancia = alvo - origem;
        double ajuste = Math.exp(-distancia / 60.0) * (desvioAtual + inclinacao * distancia);
        return limitar(base + ajuste);
    }

    private int escolherModelo(int metrica) {
        double[] erros = new double[3];
        int[] quantidades = new int[3];
        int ultimo = indice(atual);
        for (int horas : new int[]{48, 36, 24, 12}) {
            int origem = ultimo - horas * 60;
            if (!Double.isFinite(valor(metrica, origem))) continue;
            for (int horizonte : new int[]{10, 60, 180, 360}) {
                double real = valor(metrica, origem + horizonte);
                if (!Double.isFinite(real)) continue;
                for (int modelo = 0; modelo < 3; modelo++) {
                    double previsto = prever(metrica, origem, origem + horizonte, modelo);
                    if (Double.isFinite(previsto)) {
                        erros[modelo] += Math.abs(real - previsto);
                        quantidades[modelo]++;
                    }
                }
            }
        }
        int melhor = 0;
        double menor;
        if (quantidades[0] == 0) {
            menor = Double.POSITIVE_INFINITY;
        } else {
            menor = erros[0] / quantidades[0];
        }
        for (int modelo = 1; modelo < 3; modelo++) {
            if (quantidades[modelo] >= 8 && quantidades[modelo] == quantidades[0]) {
                double erro = erros[modelo] / quantidades[modelo];
                if (erro < menor * 0.98) {
                    melhor = modelo;
                    menor = erro;
                }
            }
        }
        return melhor;
    }

    public JsonObject calcular(int metrica, Double minimo, Double maximo, LocalDateTime gerado) {
        int modelo = escolherModelo(metrica);
        String[] nomes = {"Persistência", "Perfil diário", "Perfil diário com regressão linear recente"};
        JsonObject resultado = new JsonObject();
        resultado.addProperty("unidade", "%");
        numero(resultado, "limite_minimo", minimo);
        numero(resultado, "limite_maximo", maximo);
        resultado.addProperty("modelo", nomes[modelo]);
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
            // Só publicamos pontos que ainda eram futuros no momento desta execução.
            if (!futuro.isAfter(gerado)) continue;
            double valor = prever(metrica, indice(atual), indice(futuro), modelo);
            if (!Double.isFinite(valor)) valor = valor(metrica, indice(atual));
            JsonObject ponto = ponto(futuro, limitar(valor));
            previsao.add(ponto);
        }
        graficos.add("previsao", previsao);
        graficos.add("mapa", mapa(metrica));
        resultado.add("graficos", graficos);
        resultado.add("indicadores", indicadores(metrica, previsao, minimo, maximo));
        resultado.add("analise", diagnostico(metrica));
        return resultado;
    }

    private JsonObject diagnostico(int metrica) {
        JsonObject resultado = new JsonObject();
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
                    if (Double.isFinite(valor)) valores.add(valor);
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
        resultado.add("anomalias_observadas", anomalias);
        resultado.addProperty("tipo_anomalia", "Desvio das médias de minuto frente ao mesmo horário de dias anteriores");
        JsonArray correlacoes = new JsonArray();
        for (int outra = 0; outra < 3; outra++) {
            if (outra == metrica) continue;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int n = 0;
            for (int i = 0; i < minutos.length; i++) {
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

    private JsonObject indicadores(int metrica, JsonArray pontos, Double minimo, Double maximo) {
        JsonObject indicadores = new JsonObject();
        Double maior = null, menor = null;
        Integer minutos = null;
        String tipo = null;
        double atual = valor(metrica, indice(this.atual));
        if (minimo != null && atual < minimo) { minutos = 0; tipo = "abaixo"; }
        else if (maximo != null && atual > maximo) { minutos = 0; tipo = "acima"; }
        for (JsonElement elemento : pontos) {
            JsonObject ponto = elemento.getAsJsonObject();
            double valor = ponto.get("valor").getAsDouble();
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
                minutos = (int) Duration.between(this.atual, data(ponto.get("horario").getAsString())).toMinutes();
                if (minimo != null && valor < minimo) {
                    tipo = "abaixo";
                } else {
                    tipo = "acima";
                }
            }
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
