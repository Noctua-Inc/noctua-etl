package com.noctua.fetching.bucket;

import com.google.gson.*;
import java.time.*;
import java.util.*;

class CalculosPrevisoes {
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    static final String[] NOMES = {"cpu", "ram", "ocupacao"};

    static class Leitura {
        LocalDateTime horario;
        int quantidade;
        double[] valores;

        Leitura(LocalDateTime horario, int quantidade, double cpu, double ram, double disco) {
            this.horario = horario;
            this.quantidade = quantidade;
            this.valores = new double[]{cpu, ram, disco};
        }
    }

    static class Serie {
        LocalDateTime inicio;
        LocalDateTime atual;
        Leitura[] minutos;

        Serie(ArrayList<Leitura> leituras) {
            if (leituras.isEmpty()) throw new IllegalArgumentException("Histórico sem capturas.");
            leituras.sort(Comparator.comparing(l -> l.horario));
            atual = leituras.get(leituras.size() - 1).horario;
            inicio = atual.minusDays(7);
            minutos = new Leitura[7 * 1440 + 1];
            for (Leitura leitura : leituras) {
                int i = indice(leitura.horario);
                if (i >= 0 && i < minutos.length) {
                    Leitura anterior = minutos[i];
                    if (anterior == null) {
                        minutos[i] = leitura;
                    } else {
                        int total = anterior.quantidade + leitura.quantidade;
                        for (int m = 0; m < 3; m++) {
                            anterior.valores[m] = (anterior.valores[m] * anterior.quantidade
                                    + leitura.valores[m] * leitura.quantidade) / total;
                        }
                        anterior.quantidade = total;
                    }
                }
            }
        }

        int indice(LocalDateTime horario) {
            return (int) Duration.between(inicio, horario).toMinutes();
        }

        double valor(int metrica, int indice) {
            if (indice < 0 || indice >= minutos.length || minutos[indice] == null) return Double.NaN;
            return minutos[indice].valores[metrica];
        }
    }

    static String horario(LocalDateTime data) {
        return data.atZone(FUSO).toOffsetDateTime().toString();
    }

    static LocalDateTime data(String valor) {
        return OffsetDateTime.parse(valor).atZoneSameInstant(FUSO).toLocalDateTime();
    }

    static LocalDateTime inicioGrafico(LocalDateTime atual) {
        return atual.withMinute(0).minusHours(6);
    }

    static LocalDateTime fimGrafico(LocalDateTime atual) {
        return atual.withMinute(0).plusHours(6);
    }

    static JsonObject ponto(LocalDateTime horario, Double valor) {
        JsonObject ponto = new JsonObject();
        ponto.addProperty("horario", horario(horario));
        numero(ponto, "valor", valor);
        return ponto;
    }

    static void numero(JsonObject objeto, String nome, Double valor) {
        if (valor == null || !Double.isFinite(valor)) objeto.add(nome, JsonNull.INSTANCE);
        else objeto.addProperty(nome, Math.round(valor * 10000) / 10000.0);
    }

    static double limitar(double valor) {
        return Math.max(0, Math.min(100, valor));
    }

    private double mediana(ArrayList<Double> valores) {
        if (valores.isEmpty()) return Double.NaN;
        Collections.sort(valores);
        int meio = valores.size() / 2;
        if (valores.size() % 2 == 1) return valores.get(meio);
        return (valores.get(meio - 1) + valores.get(meio)) / 2;
    }

    private double perfil(Serie serie, int metrica, int origem, int alvo) {
        ArrayList<Double> dias = new ArrayList<>();
        // Cada dia contribui com uma mediana, mesmo se tiver mais capturas que outro.
        for (int dia = 1; dia <= 7; dia++) {
            int centro = alvo - dia * 1440;
            ArrayList<Double> vizinhanca = new ArrayList<>();
            for (int i = centro - 10; i <= centro + 10; i++) {
                if (i <= origem && i < alvo && i >= 0) {
                    double valor = serie.valor(metrica, i);
                    if (Double.isFinite(valor)) vizinhanca.add(valor);
                }
            }
            if (!vizinhanca.isEmpty()) dias.add(mediana(vizinhanca));
        }
        return mediana(dias);
    }

    private double prever(Serie serie, int metrica, int origem, int alvo, int modelo) {
        double atual = serie.valor(metrica, origem);
        if (modelo == 0) return atual;
        double base = perfil(serie, metrica, origem, alvo);
        if (!Double.isFinite(base)) return Double.NaN;
        if (modelo == 1) return base;

        double somaX = 0, somaY = 0, somaXX = 0, somaXY = 0;
        int quantidade = 0;
        for (int i = Math.max(0, origem - 59); i <= origem; i++) {
            double observado = serie.valor(metrica, i);
            double esperado = perfil(serie, metrica, i - 1, i);
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

    private int escolherModelo(Serie serie, int metrica) {
        double[] erros = new double[3];
        int[] quantidades = new int[3];
        int ultimo = serie.indice(serie.atual);
        for (int horas : new int[]{48, 36, 24, 12}) {
            int origem = ultimo - horas * 60;
            if (!Double.isFinite(serie.valor(metrica, origem))) continue;
            for (int horizonte : new int[]{10, 60, 180, 360}) {
                double real = serie.valor(metrica, origem + horizonte);
                if (!Double.isFinite(real)) continue;
                for (int modelo = 0; modelo < 3; modelo++) {
                    double previsto = prever(serie, metrica, origem, origem + horizonte, modelo);
                    if (Double.isFinite(previsto)) {
                        erros[modelo] += Math.abs(real - previsto);
                        quantidades[modelo]++;
                    }
                }
            }
        }
        int melhor = 0;
        double menor = quantidades[0] == 0 ? Double.POSITIVE_INFINITY : erros[0] / quantidades[0];
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

    JsonObject calcular(Serie serie, int metrica, Double minimo, Double maximo, LocalDateTime gerado) {
        int modelo = escolherModelo(serie, metrica);
        String[] nomes = {"Persistência", "Perfil diário", "Perfil diário com regressão linear recente"};
        JsonObject resultado = new JsonObject();
        resultado.addProperty("unidade", "%");
        numero(resultado, "limite_minimo", minimo);
        numero(resultado, "limite_maximo", maximo);
        resultado.addProperty("modelo", nomes[modelo]);
        JsonObject graficos = new JsonObject();
        JsonArray historico = new JsonArray();
        for (LocalDateTime hora = inicioGrafico(serie.atual); !hora.isAfter(serie.atual); hora = hora.plusHours(1)) {
            historico.add(ponto(hora, media(serie, metrica, hora, hora.plusHours(1))));
        }
        graficos.add("historico", historico);
        graficos.add("atual", ponto(serie.atual, serie.valor(metrica, serie.indice(serie.atual))));
        JsonArray previsao = new JsonArray();
        LocalDateTime futuro = serie.atual.plusMinutes(10 - serie.atual.getMinute() % 10);
        for (; !futuro.isAfter(fimGrafico(serie.atual)); futuro = futuro.plusMinutes(10)) {
            // Só publicamos pontos que ainda eram futuros no momento desta execução.
            if (!futuro.isAfter(gerado)) continue;
            double valor = prever(serie, metrica, serie.indice(serie.atual), serie.indice(futuro), modelo);
            if (!Double.isFinite(valor)) valor = serie.valor(metrica, serie.indice(serie.atual));
            JsonObject ponto = ponto(futuro, limitar(valor));
            previsao.add(ponto);
        }
        graficos.add("previsao", previsao);
        graficos.add("mapa", mapa(serie, metrica));
        resultado.add("graficos", graficos);
        resultado.add("indicadores", indicadores(serie, metrica, previsao, minimo, maximo));
        resultado.add("analise", diagnostico(serie, metrica));
        return resultado;
    }

    private JsonObject diagnostico(Serie serie, int metrica) {
        JsonObject resultado = new JsonObject();
        JsonArray anomalias = new JsonArray();
        int ultimo = serie.indice(serie.atual);
        for (int i = Math.max(0, serie.indice(inicioGrafico(serie.atual))); i <= ultimo; i++) {
            double observado = serie.valor(metrica, i);
            if (!Double.isFinite(observado)) continue;
            ArrayList<Double> dias = new ArrayList<>();
            for (int dia = 1; dia <= 7; dia++) {
                ArrayList<Double> valores = new ArrayList<>();
                for (int j = i - dia * 1440 - 10; j <= i - dia * 1440 + 10; j++) {
                    double valor = serie.valor(metrica, j);
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
                JsonObject ponto = ponto(serie.inicio.plusMinutes(i), observado);
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
            for (int i = 0; i < serie.minutos.length; i++) {
                double x = serie.valor(metrica, i), y = serie.valor(outra, i);
                if (Double.isFinite(x) && Double.isFinite(y)) {
                    sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; n++;
                }
            }
            double denominador = Math.sqrt(Math.max(0, n * sxx - sx * sx) * Math.max(0, n * syy - sy * sy));
            JsonObject item = new JsonObject();
            item.addProperty("componente", NOMES[outra]);
            numero(item, "pearson", n < 30 || denominador == 0 ? null : Math.max(-1, Math.min(1, (n * sxy - sx * sy) / denominador)));
            item.addProperty("pares", n);
            correlacoes.add(item);
        }
        resultado.add("correlacoes", correlacoes);
        return resultado;
    }

    private Double media(Serie serie, int metrica, LocalDateTime inicio, LocalDateTime fim) {
        double soma = 0;
        int quantidade = 0;
        for (int i = Math.max(0, serie.indice(inicio)); i < Math.min(serie.minutos.length, serie.indice(fim)); i++) {
            double valor = serie.valor(metrica, i);
            if (Double.isFinite(valor)) { soma += valor; quantidade++; }
        }
        return quantidade == 0 ? null : soma / quantidade;
    }

    private JsonArray mapa(Serie serie, int metrica) {
        JsonArray mapa = new JsonArray();
        LocalDate primeiro = serie.atual.toLocalDate().minusDays(6);
        for (int dia = 0; dia < 7; dia++) {
            LocalDate data = primeiro.plusDays(dia);
            for (int hora = 0; hora < 24; hora++) {
                Double valor = media(serie, metrica, data.atTime(hora, 0), data.atTime(hora, 0).plusHours(1));
                JsonObject ponto = new JsonObject();
                ponto.addProperty("dia", data.toString());
                ponto.addProperty("hora", hora);
                numero(ponto, "media", valor);
                numero(ponto, "intensidade", valor == null ? null : limitar(valor) / 100);
                mapa.add(ponto);
            }
        }
        return mapa;
    }

    private JsonObject indicadores(Serie serie, int metrica, JsonArray pontos, Double minimo, Double maximo) {
        JsonObject indicadores = new JsonObject();
        Double maior = null, menor = null;
        Integer minutos = null;
        String tipo = null;
        double atual = serie.valor(metrica, serie.indice(serie.atual));
        if (minimo != null && atual < minimo) { minutos = 0; tipo = "abaixo"; }
        else if (maximo != null && atual > maximo) { minutos = 0; tipo = "acima"; }
        for (JsonElement elemento : pontos) {
            JsonObject ponto = elemento.getAsJsonObject();
            double valor = ponto.get("valor").getAsDouble();
            maior = maior == null ? valor : Math.max(maior, valor);
            menor = menor == null ? valor : Math.min(menor, valor);
            if (minutos == null && ((minimo != null && valor < minimo) || (maximo != null && valor > maximo))) {
                minutos = (int) Duration.between(serie.atual, data(ponto.get("horario").getAsString())).toMinutes();
                tipo = minimo != null && valor < minimo ? "abaixo" : "acima";
            }
        }
        numero(indicadores, "maior_previsto", maior);
        numero(indicadores, "menor_previsto", menor);
        numero(indicadores, "primeira_saida_em_minutos", minutos == null ? null : minutos.doubleValue());
        indicadores.addProperty("primeira_saida_tipo", tipo);
        indicadores.add("erro_medio", JsonNull.INSTANCE);
        indicadores.addProperty("unidade_erro", "p.p.");
        return indicadores;
    }
}
