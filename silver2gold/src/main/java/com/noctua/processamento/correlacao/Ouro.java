package com.noctua.processamento.correlacao;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.noctua.processamento.previsoes.CalculosPrevisoes;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Ouro {
    private static final String[] RECURSOS = {"cpu", "ram", "disco"};
    private static final int[] PERIODOS_HORAS = {24, 168, 672};
    private static final int MINIMO_MEDICOES = 10;
    private static final int LIMITE_DISPERSAO = 1500;

    record Linha(LocalDateTime minuto, double cpu, double ram, double disco) {
        double valor(int recurso) {
            return switch (recurso) {
                case 0 -> cpu;
                case 1 -> ram;
                default -> disco;
            };
        }
    }

    static JsonObject gerar(String hostname, List<Linha> linhas) {
        LocalDateTime maisRecente = linhas.stream().map(Linha::minuto).max(LocalDateTime::compareTo).orElse(null);
        JsonObject documento = new JsonObject();
        documento.addProperty("hostname", hostname);
        documento.addProperty("atualizado_em", maisRecente == null ? null : CalculosPrevisoes.horario(maisRecente));
        documento.add("periodos", periodos(linhas, maisRecente));
        return documento;
    }

    private static JsonObject periodos(List<Linha> linhas, LocalDateTime maisRecente) {
        JsonObject periodos = new JsonObject();
        for (int horas : PERIODOS_HORAS) {
            LocalDateTime inicio = maisRecente == null ? null : maisRecente.minusHours(horas);
            List<Linha> selecionadas = inicio == null ? List.of()
                    : linhas.stream().filter(linha -> !linha.minuto().isBefore(inicio)).toList();
            JsonObject periodo = new JsonObject();
            periodo.addProperty("amostras", selecionadas.size());
            periodo.add("matriz", matriz(selecionadas));
            periodo.add("evolucao", evolucao(selecionadas, horas == 24 ? ChronoUnit.HOURS : ChronoUnit.DAYS));
            periodo.add("dispersao", dispersao(selecionadas));
            periodos.add(Integer.toString(horas), periodo);
        }
        return periodos;
    }

    private static JsonObject matriz(List<Linha> linhas) {
        JsonObject matriz = new JsonObject();
        for (int x = 0; x < RECURSOS.length; x++) {
            JsonObject linha = new JsonObject();
            for (int y = 0; y < RECURSOS.length; y++) {
                double[] valoresX = new double[linhas.size()];
                double[] valoresY = new double[linhas.size()];
                int n = 0;
                for (Linha leitura : linhas) {
                    double valorX = leitura.valor(x);
                    double valorY = leitura.valor(y);
                    if (!Double.isFinite(valorX) || !Double.isFinite(valorY)) continue;
                    valoresX[n] = valorX;
                    valoresY[n++] = valorY;
                }
                double r = n < MINIMO_MEDICOES ? Double.NaN : correlacao(valoresX, valoresY, n);
                linha.add(RECURSOS[y], classificar(r));
            }
            matriz.add(RECURSOS[x], linha);
        }
        return matriz;
    }

    static double correlacao(double[] x, double[] y, int n) {
        double somaX = 0;
        double somaY = 0;
        for (int i = 0; i < n; i++) {
            somaX += x[i];
            somaY += y[i];
        }
        double mediaX = somaX / n;
        double mediaY = somaY / n;

        double cima = 0;
        double baixoX = 0;
        double baixoY = 0;
        for (int i = 0; i < n; i++) {
            cima += (x[i] - mediaX) * (y[i] - mediaY);
            baixoX += (x[i] - mediaX) * (x[i] - mediaX);
            baixoY += (y[i] - mediaY) * (y[i] - mediaY);
        }

        if (baixoX == 0 || baixoY == 0) {
            return Double.NaN;
        }
        return cima / Math.sqrt(baixoX * baixoY);
    }

    private static JsonObject classificar(double r) {
        String classe;
        String texto;
        if (Double.isNaN(r)) {
            classe = "sem_dados";
            texto = "Dados insuficientes para calcular.";
        } else if (Math.abs(r) >= 0.7) {
            classe = "alta";
            texto = "Alta relação entre os recursos.";
        } else if (Math.abs(r) >= 0.4) {
            classe = "moderada";
            texto = "Relação moderada.";
        } else {
            classe = "fraca";
            texto = "Relação fraca.";
        }
        JsonObject resultado = new JsonObject();
        resultado.addProperty("valor", Double.isNaN(r) ? null : Math.max(-1, Math.min(1, r)));
        resultado.addProperty("classificacao", classe);
        resultado.addProperty("interpretacao", texto);
        return resultado;
    }

    private static JsonArray evolucao(List<Linha> linhas, ChronoUnit intervalo) {
        Map<LocalDateTime, double[]> grupos = new TreeMap<>();
        for (Linha linha : linhas) {
            double[] somas = grupos.computeIfAbsent(linha.minuto().truncatedTo(intervalo), minuto -> new double[6]);
            for (int i = 0; i < RECURSOS.length; i++) {
                double valor = linha.valor(i);
                if (!Double.isFinite(valor)) continue;
                somas[i] += valor;
                somas[i + RECURSOS.length]++;
            }
        }
        JsonArray evolucao = new JsonArray();
        grupos.forEach((minuto, somas) -> {
            JsonObject ponto = new JsonObject();
            ponto.addProperty("horario", CalculosPrevisoes.horario(minuto));
            for (int i = 0; i < RECURSOS.length; i++) {
                double quantidade = somas[i + RECURSOS.length];
                if (quantidade == 0) ponto.add(RECURSOS[i], JsonNull.INSTANCE);
                else ponto.addProperty(RECURSOS[i], somas[i] / quantidade);
            }
            evolucao.add(ponto);
        });
        return evolucao;
    }

    private static JsonArray dispersao(List<Linha> linhas) {
        List<Linha> pares = linhas.stream().filter(linha -> Double.isFinite(linha.cpu()) && Double.isFinite(linha.ram())).toList();
        int passo = Math.max(1, Math.ceilDiv(pares.size(), LIMITE_DISPERSAO));
        JsonArray dispersao = new JsonArray();
        for (int i = 0; i < pares.size(); i += passo) {
            JsonObject ponto = new JsonObject();
            ponto.addProperty("x", pares.get(i).cpu());
            ponto.addProperty("y", pares.get(i).ram());
            dispersao.add(ponto);
        }
        return dispersao;
    }
}
