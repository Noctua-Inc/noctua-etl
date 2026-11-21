package com.noctua.dashboard.previsoes;

import com.google.gson.*;
import com.noctua.model.Limite;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.noctua.dashboard.previsoes.CalculosPrevisoes.data;
import static com.noctua.dashboard.previsoes.CalculosPrevisoes.horario;
import static com.noctua.dashboard.previsoes.CalculosPrevisoes.numero;

final class IndicadoresPrevisao {
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final Duration PASSO = Duration.ofMinutes(10);

    private IndicadoresPrevisao() {}

    private record Faixa(Double minimo, Double maximo) {
        boolean valida() {
            return minimo != null && maximo != null && minimo <= maximo;
        }

        String tipo(double valor) {
            if (valor < minimo) return "abaixo";
            if (valor > maximo) return "acima";
            return null;
        }

        double limite(String tipo) {
            return "abaixo".equals(tipo) ? minimo : maximo;
        }
    }

    private record Ponto(LocalDateTime horario, Double valor) {}

    private record Periodo(LocalDateTime inicio, LocalDateTime fim) {
        int minutos() {
            return (int) Duration.between(inicio, fim).toMinutes();
        }

        JsonObject json() {
            JsonObject objeto = new JsonObject();
            objeto.addProperty("inicio", horario(inicio));
            objeto.addProperty("fim", horario(fim));
            return objeto;
        }
    }

    private record Desvio(String tipo, LocalDateTime inicio, LocalDateTime fim,
                          LocalDateTime avaliadoAte, Double extremo, double limite) {
        Double distancia() {
            return extremo == null ? null : Math.abs(extremo - limite);
        }

        Long duracao() {
            LocalDateTime fimMedida = fim != null ? fim : avaliadoAte;
            if (fimMedida == null) return null;
            Duration duracao = Duration.between(inicio, fimMedida);
            return fim == null ? duracao.toMinutes() : Math.round(duracao.toMillis() / 60000.0);
        }
    }

    private record Ocorrencia(String status, Desvio desvio) {}
    private record Linha(JsonObject dados, int prioridade, double distancia) {}
    private record Concentracao(int quantidade, Periodo periodo) {}
    private record Empresa(int comDesvio, Concentracao concentracao) {}

    static void incluirLimites(JsonObject metrica, List<Limite> limites, String tipo) {
        Double minimo = null;
        Double maximo = null;
        for (Limite limite : limites) {
            if (!tipo.equalsIgnoreCase(limite.getTipo())) continue;
            if (limite.getPicoMin() != null) {
                double valor = limite.getPicoMin();
                minimo = minimo == null ? valor : Math.max(minimo, valor);
            }
            if (limite.getPicoMax() != null) {
                double valor = limite.getPicoMax();
                maximo = maximo == null ? valor : Math.min(maximo, valor);
            }
        }
        numero(metrica, "limite_minimo", minimo);
        numero(metrica, "limite_maximo", maximo);
    }

    static void preencherMainframe(JsonObject novo, JsonObject anterior) {
        LocalDateTime coleta = data(novo.get("atualizado_em").getAsString());
        LocalDateTime gerado = data(novo.get("gerado_em").getAsString());
        LocalDateTime fim = CalculosPrevisoes.fimGrafico(coleta);
        novo.addProperty("versao_schema", 2);
        novo.addProperty("fuso_horario", CalculosPrevisoes.FUSO.getId());
        novo.addProperty("valido_ate", horario(fim));
        LocalDateTime referencia = lerData(anterior, "gerado_em");
        boolean mesmaOrigem = referencia != null && referencia.isBefore(gerado)
                && Objects.equals(novo.get("id_empresa"), anterior.get("id_empresa"))
                && Objects.equals(novo.get("id_mainframe"), anterior.get("id_mainframe"))
                && anterior.has("componentes");
        for (int m = 0; m < CalculosPrevisoes.NOMES.length; m++) {
            JsonObject metrica = JsonPrevisoes.metrica(novo, m);
            JsonObject previa = mesmaOrigem ? JsonPrevisoes.metrica(anterior, m) : null;
            metrica.add("janela_grafico", new Periodo(CalculosPrevisoes.inicioGrafico(coleta), fim).json());
            preencherIndicadores(metrica, previa, coleta, gerado, fim, referencia);
        }
    }

    private static void preencherIndicadores(JsonObject metrica, JsonObject anterior, LocalDateTime coleta,
                                             LocalDateTime gerado, LocalDateTime fim, LocalDateTime referencia) {
        Faixa faixa = faixa(metrica);
        Ocorrencia atual = localizar(metrica, faixa, gerado, fim);
        String unidade = metrica.get("unidade").getAsString();
        String unidadeDistancia = unidade.equals("%") ? "p.p." : unidade;
        Desvio desvio = atual.desvio();
        JsonObject proximo = new JsonObject();
        proximo.addProperty("status", atual.status());
        proximo.addProperty("tipo", desvio == null ? null : desvio.tipo());
        proximo.addProperty("inicio", desvio == null || atual.status().equals("em_desvio") ? null : horario(desvio.inicio()));
        proximo.addProperty("fim", desvio == null || desvio.fim() == null ? null : horario(desvio.fim()));
        proximo.addProperty("retorno_dentro_do_horizonte", desvio != null && desvio.fim() != null);
        metrica.add("proximo_desvio", proximo);

        JsonObject tempo;
        JsonObject duracao;
        JsonObject intensidade;
        if (desvio == null) {
            tempo = kpi(atual.status(), null, "min", rotulo(atual.status()), "");
            duracao = kpi(atual.status(), null, "min", "—", "");
            intensidade = kpi(atual.status(), null, unidade, "—", "");
        } else {
            boolean observado = atual.status().equals("em_desvio");
            long minutosAte = Duration.between(coleta, desvio.inicio()).toMinutes();
            String detalheTempo = observado ? "Observado na coleta das " + HORA.format(coleta)
                    : "Início previsto às " + HORA.format(desvio.inicio()) + " · coleta das " + HORA.format(coleta);
            tempo = kpi("disponivel", (double) minutosAte, "min",
                    observado ? "Já fora da faixa" : minutosAte + " min", detalheTempo);

            Long minutos = desvio.duracao();
            boolean parcial = desvio.fim() == null;
            String detalheDuracao = observado ? "A partir da coleta"
                    : parcial ? "Retorno à faixa ainda indisponível"
                    : "Retorno previsto às " + HORA.format(desvio.fim().plusSeconds(30));
            String rotuloDuracao = minutos == null ? "—" : (parcial ? "≥ " : "≈ ") + formatarDuracao(minutos);
            duracao = kpi(minutos == null ? "sem_dados" : parcial ? "parcial" : "disponivel",
                    minutos == null ? null : minutos.doubleValue(), "min", rotuloDuracao, detalheDuracao);

            Double extremo = desvio.extremo();
            String direcao = "abaixo".equals(desvio.tipo()) ? "abaixo" : "acima";
            String detalheIntensidade = extremo == null ? "Sem extremo previsto para este desvio"
                    : formatar(desvio.distancia(), unidadeDistancia) + " " + direcao + " do limite de " + formatar(desvio.limite(), unidade);
            intensidade = kpi(extremo == null ? "sem_dados" : "disponivel", extremo, unidade,
                    extremo == null ? "—" : formatar(extremo, unidade), detalheIntensidade);
        }
        numero(intensidade, "valor_extremo", desvio == null ? null : desvio.extremo());
        numero(intensidade, "limite_violado", desvio == null ? null : desvio.limite());
        numero(intensidade, "distancia_do_limite", desvio == null ? null : desvio.distancia());
        intensidade.addProperty("unidade_distancia", unidadeDistancia);

        if (anterior != null && faixa.valida() && faixa.equals(faixa(anterior))
                && metrica.get("unidade").equals(anterior.get("unidade"))) {
            Ponto coletaAnterior = pontoAtual(anterior);
            if (coletaAnterior != null) {
                compararDesvios(atual, localizar(anterior, faixa, referencia,
                                CalculosPrevisoes.fimGrafico(coletaAnterior.horario())),
                        tempo, duracao, intensidade, gerado, referencia, fim,
                        CalculosPrevisoes.fimGrafico(coletaAnterior.horario()), unidadeDistancia);
            }
        }
        JsonObject indicadores = new JsonObject();
        indicadores.add("tempo_ate_desvio", tempo);
        indicadores.add("duracao_desvio", duracao);
        indicadores.add("intensidade_desvio", intensidade);
        metrica.add("indicadores", indicadores);
    }

    private static Ocorrencia localizar(JsonObject metrica, Faixa faixa, LocalDateTime gerado, LocalDateTime fim) {
        if (!gerado.isBefore(fim)) return new Ocorrencia("previsao_vencida", null);
        if (!faixa.valida()) return new Ocorrencia("sem_limites", null);
        Ponto anterior = pontoAtual(metrica);
        if (anterior == null || anterior.valor() == null) return new Ocorrencia("sem_dados", null);
        String tipo = faixa.tipo(anterior.valor());
        boolean observado = tipo != null;
        LocalDateTime inicio = observado ? anterior.horario() : null;
        LocalDateTime avaliadoAte = null;
        LocalDateTime retorno = null;
        Double extremo = null;
        boolean lacuna = false;
        LocalDateTime esperado = passoSeguinte(gerado);
        for (Ponto ponto : pontos(metrica)) {
            if (!ponto.horario().isAfter(gerado) || ponto.horario().isAfter(fim)) continue;
            boolean continuo = anterior != null && anterior.valor() != null && ponto.valor() != null
                    && Duration.between(anterior.horario(), ponto.horario()).compareTo(PASSO) <= 0;
            if (!esperado.equals(ponto.horario()) || ponto.valor() == null) lacuna = true;
            esperado = ponto.horario().plus(PASSO);
            if (inicio != null && !continuo) break;
            if (ponto.valor() == null) {
                anterior = ponto;
                continue;
            }
            String lado = faixa.tipo(ponto.valor());
            if (inicio != null && !tipo.equals(lado)) {
                retorno = cruzamento(anterior, ponto, faixa.limite(tipo));
                break;
            }
            if (inicio == null && lado != null) {
                tipo = lado;
                inicio = ponto.horario();
                if (continuo) {
                    LocalDateTime cruzou = cruzamento(anterior, ponto, faixa.limite(tipo));
                    inicio = menor(ponto.horario(), maior(minutoSeguinte(cruzou), minutoSeguinte(gerado)));
                }
            }
            if (inicio != null) {
                avaliadoAte = ponto.horario();
                if (extremo == null || Math.abs(ponto.valor() - faixa.limite(tipo)) > Math.abs(extremo - faixa.limite(tipo))) {
                    extremo = ponto.valor();
                }
            }
            anterior = ponto;
        }
        if (inicio != null) {
            String status = observado ? "em_desvio" : "previsto";
            return new Ocorrencia(status, new Desvio(tipo, inicio, retorno, avaliadoAte, extremo, faixa.limite(tipo)));
        }
        return new Ocorrencia(!lacuna && esperado.isAfter(fim) ? "sem_desvio" : "sem_dados", null);
    }

    private static void compararDesvios(Ocorrencia atual, Ocorrencia anterior, JsonObject tempo,
                                        JsonObject duracao, JsonObject intensidade, LocalDateTime gerado,
                                        LocalDateTime referencia, LocalDateTime fim, LocalDateTime fimAnterior,
                                        String unidadeDistancia) {
        Desvio novo = atual.desvio();
        Desvio previo = anterior.desvio();
        LocalDateTime fimComum = menor(fim, fimAnterior);
        if (novo != null && atual.status().equals("previsto") && anterior.status().equals("sem_desvio")
                && novo.inicio().isBefore(fimComum)) {
            tempo.add("comparacao", comparacao("novo_desvio", referencia, null, "min", null, "Novo desvio previsto"));
            return;
        }
        if (previo != null && anterior.status().equals("previsto") && atual.status().equals("sem_desvio")
                && previo.inicio().isAfter(gerado) && previo.inicio().isBefore(fimComum)) {
            tempo.add("comparacao", comparacao("desvio_deixou_de_ser_previsto", referencia,
                    null, "min", null, "Desvio deixou de ser previsto"));
            return;
        }
        if (!atual.status().equals("previsto") || !anterior.status().equals("previsto")
                || !novo.tipo().equals(previo.tipo()) || !previo.inicio().isAfter(gerado)
                || !maior(novo.inicio(), previo.inicio()).isBefore(menor(fimComum,
                    menor(fimConhecido(novo), fimConhecido(previo))))) return;

        double variacaoInicio = Duration.between(previo.inicio(), novo.inicio()).toMinutes();
        tempo.add("comparacao", comparar(referencia, variacaoInicio, "min", false, "Início mais tarde", "Início mais cedo"));
        if (novo.fim() != null && previo.fim() != null
                && !novo.fim().isAfter(fimComum) && !previo.fim().isAfter(fimComum)) {
            duracao.add("comparacao", comparar(referencia, novo.duracao() - previo.duracao(),
                    "min", true, "Maior duração", "Menor duração"));
            if (novo.distancia() != null && previo.distancia() != null) {
                intensidade.add("comparacao", comparar(referencia, novo.distancia() - previo.distancia(),
                        unidadeDistancia, true, "Maior desvio do limite", "Menor desvio do limite"));
            }
        }
    }

    private static LocalDateTime fimConhecido(Desvio desvio) {
        if (desvio.fim() != null) return desvio.fim();
        return desvio.avaliadoAte() != null ? desvio.avaliadoAte() : desvio.inicio();
    }

    static JsonObject resumirMetrica(List<JsonObject> documentos, int indice, int cadastradas,
                                    LocalDateTime inicio, LocalDateTime fim, LocalDateTime gerado,
                                    JsonObject anterior, LocalDateTime referencia) {
        Periodo periodo = new Periodo(inicio, fim);
        String unidade = indice < 3 ? "%" : "MiB/s";
        List<Linha> linhas = new ArrayList<>();
        boolean vencida = !gerado.isBefore(fim);
        for (JsonObject documento : documentos) {
            linhas.add(resumirMainframe(documento, indice, periodo, unidade, vencida));
        }
        linhas.sort(Comparator.comparingInt(Linha::prioridade).reversed()
                .thenComparing(Comparator.comparingDouble(Linha::distancia).reversed())
                .thenComparingInt(linha -> linha.dados().get("id_mainframe").getAsInt()));
        JsonArray mainframes = new JsonArray();
        for (Linha linha : linhas) {
            linha.dados().addProperty("posicao", mainframes.size() + 1);
            mainframes.add(linha.dados());
        }
        Map<Integer, JsonObject> avaliadas = avaliadas(mainframes);
        Empresa atual = agregar(avaliadas, periodo);
        JsonObject concentracao = kpiConcentracao(atual.concentracao(), avaliadas.size(), vencida);
        JsonObject comDesvio = kpiMainframes(atual.comDesvio(), avaliadas.size(), periodo, vencida);
        compararEmpresa(concentracao, comDesvio, avaliadas, periodo, gerado, anterior, referencia);

        JsonObject cobertura = new JsonObject();
        cobertura.addProperty("cadastradas", cadastradas);
        cobertura.addProperty("avaliadas", avaliadas.size());
        cobertura.addProperty("sem_avaliacao", cadastradas - avaliadas.size());
        JsonObject indicadores = new JsonObject();
        indicadores.add("maior_concentracao", concentracao);
        indicadores.add("mainframes_com_desvio", comDesvio);
        JsonObject resultado = new JsonObject();
        resultado.addProperty("unidade", unidade);
        resultado.add("periodo_avaliado", periodo.json());
        resultado.add("cobertura", cobertura);
        resultado.add("indicadores", indicadores);
        resultado.add("mainframes", mainframes);
        return resultado;
    }

    private static Linha resumirMainframe(JsonObject documento, int indice, Periodo periodo, String unidade,
                                          boolean vencida) {
        JsonObject metrica = JsonPrevisoes.metrica(documento, indice);
        Faixa faixa = faixa(metrica);
        double[] valores = vencida ? null : valoresNoPeriodo(pontos(metrica), periodo);
        Double menor = valores == null ? null : Arrays.stream(valores).min().orElseThrow();
        Double maior = valores == null ? null : Arrays.stream(valores).max().orElseThrow();
        String status;
        String situacao;
        String destaque = "neutro";
        int prioridade = 0;
        Double distancia = null;
        JsonArray intervalos = new JsonArray();
        if (vencida || !faixa.valida() || valores == null) {
            status = vencida ? "previsao_vencida" : !faixa.valida() ? "sem_limites" : "sem_dados";
            situacao = rotulo(status);
        } else {
            status = "avaliada";
            double abaixo = faixa.minimo() - menor;
            double acima = maior - faixa.maximo();
            distancia = Math.max(0, Math.max(abaixo, acima));
            if (abaixo > 0 && acima > 0) {
                prioridade = 4;
                situacao = "Abaixo e acima";
                destaque = "alerta";
            } else if (acima > 0) {
                prioridade = 3;
                situacao = "Acima do máximo";
                destaque = "atencao";
            } else if (abaixo > 0) {
                prioridade = 2;
                situacao = "Abaixo do mínimo";
                destaque = "atencao";
            } else {
                prioridade = 1;
                situacao = "Dentro da faixa";
                destaque = "normal";
            }
            intervalos = intervalosDesvio(valores, faixa, periodo.inicio());
        }
        JsonObject linha = new JsonObject();
        linha.add("id_mainframe", documento.get("id_mainframe"));
        linha.add("hostname", documento.get("hostname"));
        linha.add("previsao_gerada_em", documento.get("gerado_em"));
        linha.add("previsao_atualizada_em", documento.get("atualizado_em"));
        linha.add("capturas_no_minuto", documento.get("capturas_no_minuto"));
        linha.addProperty("status", status);
        linha.addProperty("situacao", situacao);
        numero(linha, "menor_previsto", menor);
        numero(linha, "maior_previsto", maior);
        numero(linha, "limite_minimo", faixa.minimo());
        numero(linha, "limite_maximo", faixa.maximo());
        numero(linha, "distancia_do_limite", distancia);
        linha.addProperty("unidade_distancia", unidade.equals("%") ? "p.p." : unidade);
        // Os intervalos permitem comparar dois resumos no mesmo recorte após o avanço da janela.
        linha.add("intervalos_desvio", intervalos);
        JsonObject exibicao = new JsonObject();
        exibicao.addProperty("faixa_prevista", formatarFaixa(menor, maior, unidade));
        exibicao.addProperty("limites", formatarFaixa(faixa.minimo(), faixa.maximo(), unidade));
        exibicao.addProperty("situacao", situacao);
        exibicao.addProperty("destaque", destaque);
        linha.add("exibicao", exibicao);
        return new Linha(linha, prioridade, distancia == null ? 0 : distancia);
    }

    private static double[] valoresNoPeriodo(List<Ponto> pontos, Periodo periodo) {
        double[] valores = new double[periodo.minutos() + 1];
        int candidato = 0;
        for (int i = 0; i < valores.length; i++) {
            LocalDateTime horario = periodo.inicio().plusMinutes(i);
            while (candidato < pontos.size() && pontos.get(candidato).horario().isBefore(horario)) candidato++;
            if (candidato >= pontos.size()) return null;
            Ponto depois = pontos.get(candidato);
            if (depois.valor() == null) return null;
            if (depois.horario().equals(horario)) {
                valores[i] = depois.valor();
            } else {
                if (candidato == 0) return null;
                Ponto antes = pontos.get(candidato - 1);
                Duration intervalo = Duration.between(antes.horario(), depois.horario());
                if (antes.valor() == null || intervalo.compareTo(PASSO) > 0) return null;
                double fracao = (double) Duration.between(antes.horario(), horario).toNanos() / intervalo.toNanos();
                valores[i] = antes.valor() + (depois.valor() - antes.valor()) * fracao;
            }
        }
        return valores;
    }

    private static JsonArray intervalosDesvio(double[] valores, Faixa faixa, LocalDateTime inicio) {
        JsonArray intervalos = new JsonArray();
        int primeiro = -1;
        for (int i = 0; i < valores.length; i++) {
            // Cada posição continua representando [minuto, minuto seguinte).
            boolean desvio = i + 1 < valores.length
                    && (faixa.tipo(valores[i]) != null || faixa.tipo(valores[i + 1]) != null);
            if (desvio && primeiro < 0) primeiro = i;
            if (!desvio && primeiro >= 0) {
                intervalos.add(new Periodo(inicio.plusMinutes(primeiro), inicio.plusMinutes(i)).json());
                primeiro = -1;
            }
        }
        return intervalos;
    }

    private static String formatarFaixa(Double minimo, Double maximo, String unidade) {
        if (minimo == null && maximo == null) return "—";
        return (minimo == null ? "—" : formatar(minimo, unidade))
                + " – " + (maximo == null ? "—" : formatar(maximo, unidade));
    }

    private static Map<Integer, JsonObject> avaliadas(JsonArray linhas) {
        Map<Integer, JsonObject> avaliadas = new LinkedHashMap<>();
        for (JsonElement elemento : linhas) {
            JsonObject linha = elemento.getAsJsonObject();
            if ("avaliada".equals(linha.get("status").getAsString())) {
                avaliadas.put(linha.get("id_mainframe").getAsInt(), linha);
            }
        }
        return avaliadas;
    }

    private static Empresa agregar(Map<Integer, JsonObject> avaliadas, Periodo periodo) {
        int[] diferencas = new int[periodo.minutos() + 1];
        int comDesvio = 0;
        for (JsonObject linha : avaliadas.values()) {
            boolean desvio = false;
            for (JsonElement elemento : linha.getAsJsonArray("intervalos_desvio")) {
                JsonObject intervalo = elemento.getAsJsonObject();
                LocalDateTime inicio = maior(periodo.inicio(), lerData(intervalo, "inicio"));
                LocalDateTime fim = menor(periodo.fim(), lerData(intervalo, "fim"));
                if (!inicio.isBefore(fim)) continue;
                diferencas[(int) Duration.between(periodo.inicio(), inicio).toMinutes()]++;
                diferencas[(int) Duration.between(periodo.inicio(), fim).toMinutes()]--;
                desvio = true;
            }
            if (desvio) comDesvio++;
        }
        int pico = 0;
        int simultaneas = 0;
        int primeiro = 0;
        int fimPico = 0;
        for (int i = 0; i < diferencas.length - 1; i++) {
            simultaneas += diferencas[i];
            if (simultaneas > pico) {
                pico = simultaneas;
                primeiro = i;
                fimPico = i + 1;
            } else if (simultaneas == pico && pico > 0 && i == fimPico) {
                fimPico++;
            }
        }
        Periodo janelaPico = pico == 0 ? null
                : new Periodo(periodo.inicio().plusMinutes(primeiro), periodo.inicio().plusMinutes(fimPico));
        return new Empresa(comDesvio, new Concentracao(pico, janelaPico));
    }

    private static JsonObject kpiConcentracao(Concentracao concentracao, int avaliadas, boolean vencida) {
        int quantidade = concentracao.quantidade();
        Periodo periodo = concentracao.periodo();
        String status = vencida ? "previsao_vencida" : avaliadas == 0 ? "sem_dados"
                : quantidade == 0 ? "sem_desvio" : "disponivel";
        String exibicao = periodo == null ? rotulo(status) : HORA.format(periodo.inicio()) + "–" + HORA.format(periodo.fim());
        String detalhe = quantidade == 0 ? "" : quantidade == 1 ? "1 mainframe com desvio previsto"
                : quantidade + " mainframes em desvio simultâneo";
        JsonObject kpi = kpi(status, avaliadas == 0 ? null : (double) quantidade, "mainframes", exibicao, detalhe);
        kpi.addProperty("inicio", periodo == null ? null : horario(periodo.inicio()));
        kpi.addProperty("fim", periodo == null ? null : horario(periodo.fim()));
        numero(kpi, "quantidade_simultanea", avaliadas == 0 ? null : (double) quantidade);
        return kpi;
    }

    private static JsonObject kpiMainframes(int comDesvio, int avaliadas, Periodo periodo, boolean vencida) {
        String status = vencida ? "previsao_vencida" : avaliadas == 0 ? "sem_dados" : "disponivel";
        String detalhe = avaliadas == 0 ? rotulo(status) : "Mainframes avaliadas até " + HORA.format(periodo.fim());
        JsonObject kpi = kpi(status, avaliadas == 0 ? null : (double) comDesvio, "mainframes",
                avaliadas == 0 ? "—" : comDesvio + " de " + avaliadas, detalhe);
        kpi.addProperty("quantidade", comDesvio);
        kpi.addProperty("total_avaliadas", avaliadas);
        return kpi;
    }

    private static void compararEmpresa(JsonObject concentracao, JsonObject comDesvio,
                                        Map<Integer, JsonObject> atuais, Periodo periodo, LocalDateTime gerado,
                                        JsonObject anterior, LocalDateTime referencia) {
        if (atuais.isEmpty() || anterior == null || referencia == null || !referencia.isBefore(gerado)) return;
        Map<Integer, JsonObject> anteriores = avaliadas(anterior.getAsJsonArray("mainframes"));
        if (!atuais.keySet().equals(anteriores.keySet())) return;
        for (var entrada : atuais.entrySet()) {
            JsonObject previa = anteriores.get(entrada.getKey());
            if (!faixa(entrada.getValue()).equals(faixa(previa)) || !previa.has("intervalos_desvio")) return;
        }
        JsonObject periodoAnterior = anterior.getAsJsonObject("periodo_avaliado");
        LocalDateTime futuro = gerado.truncatedTo(ChronoUnit.MINUTES);
        if (futuro.isBefore(gerado)) futuro = futuro.plusMinutes(1);
        Periodo comum = new Periodo(maior(futuro, maior(periodo.inicio(), lerData(periodoAnterior, "inicio"))),
                menor(periodo.fim(), lerData(periodoAnterior, "fim")));
        if (comum.minutos() <= 0) return;
        Empresa novo = agregar(atuais, comum);
        Empresa previo = agregar(anteriores, comum);
        JsonObject compararPico = comparar(referencia, novo.concentracao().quantidade() - previo.concentracao().quantidade(),
                "mainframes", true, "Mais mainframes simultâneas", "Menos mainframes simultâneas");
        JsonObject compararTotal = comparar(referencia, novo.comDesvio() - previo.comDesvio(), "mainframes",
                true, "Mais mainframes com desvio", "Menos mainframes com desvio");
        compararPico.add("periodo", comum.json());
        compararTotal.add("periodo", comum.json());
        concentracao.add("comparacao", compararPico);
        comDesvio.add("comparacao", compararTotal);
    }

    private static String rotulo(String status) {
        return switch (status) {
            case "sem_desvio" -> "Sem desvio previsto";
            case "sem_limites" -> "Limites indisponíveis";
            case "previsao_vencida" -> "Previsão vencida";
            default -> "Dados insuficientes";
        };
    }

    private static JsonObject kpi(String status, Double valor, String unidade, String exibicao, String detalhe) {
        JsonObject kpi = new JsonObject();
        kpi.addProperty("status", status);
        numero(kpi, "valor", valor);
        kpi.addProperty("unidade", unidade);
        JsonObject tela = new JsonObject();
        tela.addProperty("valor", exibicao);
        tela.addProperty("detalhe", detalhe);
        kpi.add("exibicao", tela);
        kpi.add("comparacao", comparacao("sem_comparacao", null, null, unidade, null, "Sem comparação"));
        return kpi;
    }

    private static JsonObject comparar(LocalDateTime referencia, double variacao, String unidade,
                                       boolean aumentoPiora, String aumento, String reducao) {
        double delta = Math.round(variacao * 100) / 100.0;
        String avaliacao = delta == 0 ? "estavel" : (delta > 0) == aumentoPiora ? "piora" : "melhora";
        String descricao = delta == 0 ? "Sem mudança" : formatar(Math.abs(delta), unidade) + " · " + (delta > 0 ? aumento : reducao);
        return comparacao("comparavel", referencia, delta, unidade, avaliacao, descricao);
    }

    private static JsonObject comparacao(String status, LocalDateTime referencia, Double variacao,
                                         String unidade, String avaliacao, String texto) {
        JsonObject resultado = new JsonObject();
        resultado.addProperty("status", status);
        resultado.addProperty("referencia_gerada_em", referencia == null ? null : horario(referencia));
        numero(resultado, "variacao", variacao);
        resultado.addProperty("unidade", unidade);
        resultado.addProperty("direcao", variacao == null ? null : variacao == 0 ? "igual" : variacao > 0 ? "cima" : "baixo");
        resultado.addProperty("avaliacao", avaliacao);
        resultado.addProperty("texto", texto);
        return resultado;
    }

    private static String formatarDuracao(long minutos) {
        if (minutos < 60) return minutos + " min";
        long resto = minutos % 60;
        return minutos / 60 + " h" + (resto == 0 ? "" : " " + resto + " min");
    }

    private static String formatar(double valor, String unidade) {
        NumberFormat formato = NumberFormat.getNumberInstance(Locale.forLanguageTag("pt-BR"));
        formato.setMaximumFractionDigits(2);
        return formato.format(valor) + (unidade.equals("%") || unidade.isEmpty() ? "" : " ") + unidade;
    }

    private static Double lerNumero(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        if (valor == null || valor.isJsonNull()) return null;
        double numero = valor.getAsDouble();
        return Double.isFinite(numero) ? numero : null;
    }

    static LocalDateTime lerData(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        return valor == null || valor.isJsonNull() ? null : data(valor.getAsString());
    }

    private static Faixa faixa(JsonObject metrica) {
        return new Faixa(lerNumero(metrica, "limite_minimo"), lerNumero(metrica, "limite_maximo"));
    }

    private static Ponto pontoAtual(JsonObject metrica) {
        JsonObject atual = metrica.getAsJsonObject("graficos").getAsJsonObject("atual");
        return atual == null ? null : ponto(atual);
    }

    private static List<Ponto> pontos(JsonObject metrica) {
        List<Ponto> pontos = new ArrayList<>();
        for (JsonElement elemento : metrica.getAsJsonObject("graficos").getAsJsonArray("previsao")) {
            pontos.add(ponto(elemento.getAsJsonObject()));
        }
        return pontos;
    }

    private static Ponto ponto(JsonObject objeto) {
        return new Ponto(lerData(objeto, "horario"), lerNumero(objeto, "valor"));
    }

    static LocalDateTime passoSeguinte(LocalDateTime horario) {
        return horario.truncatedTo(ChronoUnit.MINUTES).plusMinutes(10 - horario.getMinute() % 10);
    }

    private static LocalDateTime minutoSeguinte(LocalDateTime horario) {
        return horario.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    }

    private static LocalDateTime maior(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime menor(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDateTime cruzamento(Ponto antes, Ponto depois, double limite) {
        long nanos = Duration.between(antes.horario(), depois.horario()).toNanos();
        double fracao = (limite - antes.valor()) / (depois.valor() - antes.valor());
        return antes.horario().plusNanos(Math.round(fracao * nanos));
    }
}
