package com.noctua.processamento.alertas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.noctua.infraestrutura.banco.ConsultasAlertas.MainframeAlertas;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class ResumoAlertas {
    static final List<String> COMPONENTES = List.of("RAM", "CPU", "DISCO", "SWAP");
    private static final List<Integer> PERIODOS = List.of(6, 24, 168);
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    record Avaliacao(Instant horario, String componente, double desvio) {}
    private record Equipamento(String hostname, String localizacao, boolean monitorado) {}
    private record Linha(String mainframe, String componente, String inicio, String resolvidoEm,
                         String status, String severidade, boolean reincidente, long minutos) {}
    private record Fluxo(List<String> rotulos, int[] abertos, int[] resolvidos) {}
    private record Painel(int ativos, int equipamentosComAlertas, long monitoradas, Long minutosResolucao,
                         Double percentualReincidencia, int totalAbertos, int totalResolvidos,
                         Fluxo fluxo, int[] componentes, Map<String, int[]> tempo, List<Integer> historico) {}
    private record Documento(String atualizadoEm, long monitoradas, List<Equipamento> mainframes,
                             List<String> localizacoes, List<Linha> ocorrencias, Map<Integer, Painel> todos,
                             Map<String, Map<Integer, Painel>> porMainframe, Map<String, Map<Integer, Painel>> porLocalizacao) {}

    private static final class Ocorrencia {
        final String mainframe;
        final String componente;
        final Instant inicio;
        final boolean reincidente;
        Instant fim;
        double maiorDesvio;

        Ocorrencia(String mainframe, Avaliacao avaliacao, boolean reincidente) {
            this.mainframe = mainframe;
            this.componente = avaliacao.componente();
            this.inicio = avaliacao.horario();
            this.reincidente = reincidente;
        }

        String severidade() {
            if (maiorDesvio >= 20) return "critica";
            if (maiorDesvio >= 10) return "alta";
            return "moderada";
        }

        long minutos(Instant agora) {
            return (long) Math.ceil(Duration.between(inicio, fim == null ? agora : fim).toMillis() / 60_000.0);
        }

        Linha exibir(Instant agora) {
            return new Linha(mainframe, componente, data(inicio), data(fim), fim == null ? "aberto" : "resolvido",
                    severidade(), reincidente, minutos(agora));
        }
    }

    static String gerar(Map<MainframeAlertas, List<Avaliacao>> leituras, Instant agora) {
        var equipamentos = new ArrayList<Equipamento>();
        var ocorrencias = new ArrayList<Ocorrencia>();
        Instant atualizacao = null;
        for (var entrada : leituras.entrySet()) {
            MainframeAlertas mainframe = entrada.getKey();
            List<Avaliacao> amostras = entrada.getValue().stream()
                    .filter(a -> !a.horario().isAfter(agora))
                    .sorted(Comparator.comparing(Avaliacao::horario).thenComparing(Avaliacao::componente)
                            .thenComparing(Comparator.comparingDouble(Avaliacao::desvio).reversed()))
                    .toList();
            equipamentos.add(new Equipamento(mainframe.hostname(), mainframe.localizacao(), !amostras.isEmpty()));
            if (amostras.isEmpty()) continue;
            Instant ultima = amostras.getLast().horario();
            if (atualizacao == null || ultima.isAfter(atualizacao)) atualizacao = ultima;
            ocorrencias.addAll(detectar(mainframe.hostname(), amostras));
        }
        Instant janela = agora.minus(Duration.ofDays(7));
        ocorrencias.removeIf(alerta -> alerta.fim != null && alerta.fim.isBefore(janela));
        ocorrencias.sort(Comparator.comparing((Ocorrencia a) -> a.inicio).reversed().thenComparing(a -> a.mainframe)
                .thenComparing(a -> a.componente));

        var porMainframe = new TreeMap<String, Map<Integer, Painel>>();
        var porLocalizacao = new TreeMap<String, Map<Integer, Painel>>();
        var localidades = new TreeMap<String, List<Equipamento>>();
        for (Equipamento dispositivo : equipamentos) {
            porMainframe.put(dispositivo.hostname(), paineis(List.of(dispositivo), ocorrencias, agora));
            localidades.computeIfAbsent(dispositivo.localizacao(), local -> new ArrayList<>()).add(dispositivo);
        }
        localidades.forEach((local, dispositivos) -> porLocalizacao.put(local, paineis(dispositivos, ocorrencias, agora)));
        return JSON.toJson(new Documento(data(atualizacao), equipamentos.stream().filter(Equipamento::monitorado).count(),
                equipamentos, List.copyOf(localidades.keySet()), ocorrencias.stream().map(a -> a.exibir(agora)).toList(),
                paineis(equipamentos, ocorrencias, agora), porMainframe, porLocalizacao));
    }

    private static List<Ocorrencia> detectar(String hostname, List<Avaliacao> avaliacoes) {
        var ocorrencias = new ArrayList<Ocorrencia>();
        var abertas = new HashMap<String, Ocorrencia>();
        var anteriores = new HashSet<String>();
        Avaliacao anterior = null;
        for (Avaliacao atual : avaliacoes) {
            String componente = atual.componente();
            if (anterior != null && anterior.horario().equals(atual.horario()) && anterior.componente().equals(componente)) continue;
            anterior = atual;
            if (atual.desvio() == 0) {
                Ocorrencia resolvida = abertas.remove(componente);
                if (resolvida != null) resolvida.fim = atual.horario();
                continue;
            }
            Ocorrencia aberta = abertas.get(componente);
            if (aberta == null) {
                aberta = new Ocorrencia(hostname, atual, !anteriores.add(componente));
                abertas.put(componente, aberta);
                ocorrencias.add(aberta);
            }
            aberta.maiorDesvio = Math.max(aberta.maiorDesvio, atual.desvio());
        }
        return ocorrencias;
    }

    private static Map<Integer, Painel> paineis(List<Equipamento> equipamentos, List<Ocorrencia> alertas, Instant agora) {
        Set<String> hosts = new HashSet<>();
        for (Equipamento equipamento : equipamentos) hosts.add(equipamento.hostname());
        long monitoradas = equipamentos.stream().filter(Equipamento::monitorado).count();
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < alertas.size(); i++) {
            if (hosts.contains(alertas.get(i).mainframe)) indices.add(i);
        }
        var paineis = new TreeMap<Integer, Painel>();
        for (int horas : PERIODOS) paineis.put(horas, painel(horas, monitoradas, alertas, indices, agora));
        return paineis;
    }

    private static Painel painel(int horas, long monitoradas, List<Ocorrencia> alertas, List<Integer> indices, Instant agora) {
        Instant inicio = agora.minus(Duration.ofHours(horas));
        int intervalos = horas == 168 ? 7 : 6;
        long tamanho = Duration.ofHours(horas).toMillis() / intervalos;
        var rotulos = new ArrayList<String>();
        var formato = DateTimeFormatter.ofPattern(horas == 168 ? "dd/MM" : "HH'h'").withZone(FUSO);
        for (int i = 1; i <= intervalos; i++) rotulos.add(formato.format(inicio.plusMillis(i * tamanho)));
        int[] abertos = new int[intervalos];
        int[] resolvidos = new int[intervalos];
        int[] componentes = new int[COMPONENTES.size()];
        var tempo = Map.of("critica", new int[4], "alta", new int[4], "moderada", new int[4]);
        var dispositivosComAlertas = new HashSet<String>();
        var historico = new ArrayList<Integer>();
        int ativos = 0;
        int resolvidosNoPeriodo = 0;
        int reincidentes = 0;
        double totalMinutos = 0;

        for (int indice : indices) {
            Ocorrencia alerta = alertas.get(indice);
            if (!alerta.inicio.isBefore(inicio)) {
                historico.add(indice);
                if (alerta.reincidente) reincidentes++;
                abertos[intervalo(inicio, alerta.inicio, tamanho, intervalos)]++;
                componentes[COMPONENTES.indexOf(alerta.componente)]++;
            }
            if (alerta.fim == null) {
                ativos++;
                dispositivosComAlertas.add(alerta.mainframe);
                long minutos = alerta.minutos(agora);
                int faixa = minutos > 60 ? 3 : minutos > 30 ? 2 : minutos > 15 ? 1 : 0;
                tempo.get(alerta.severidade())[faixa]++;
            } else if (!alerta.fim.isBefore(inicio)) {
                resolvidosNoPeriodo++;
                totalMinutos += Duration.between(alerta.inicio, alerta.fim).toMillis() / 60_000.0;
                resolvidos[intervalo(inicio, alerta.fim, tamanho, intervalos)]++;
            }
        }
        Long resolucao = resolvidosNoPeriodo == 0 ? null : Math.round(totalMinutos / resolvidosNoPeriodo);
        Double taxa = historico.isEmpty() ? null : 100.0 * reincidentes / historico.size();
        return new Painel(ativos, dispositivosComAlertas.size(), monitoradas, resolucao, taxa, historico.size(), resolvidosNoPeriodo,
                new Fluxo(rotulos, abertos, resolvidos), componentes, tempo, historico);
    }

    private static int intervalo(Instant inicio, Instant horario, long tamanho, int quantidade) {
        return (int) Math.min(quantidade - 1, Duration.between(inicio, horario).toMillis() / tamanho);
    }

    private static String data(Instant horario) {
        return horario == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(horario.atZone(FUSO));
    }
}
