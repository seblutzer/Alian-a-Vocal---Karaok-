
package com.karaoke;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.util.*;

public class MusicXMLParser {

    // ── Evento de mudança de andamento ────────────────────────────────────────
    // Guarda a posição absoluta (em ticks/divisions) e o BPM resultante.
    // beatUnitDivisions = quantos "divisions" tem a nota de referência do metrônomo.
    // Ex.: beat-unit=quarter e divisions=256  →  beatUnitDivisions = 256
    //      beat-unit=half                     →  beatUnitDivisions = 512
    //      beat-unit=eighth                   →  beatUnitDivisions = 128
    private static class TempoEvent {
        long   tickAbsolute;      // posição absoluta em ticks (contados desde o início)
        double bpm;               // BPM da nota de referência
        int    beatUnitDivisions; // duração (em divisions) da nota de referência

        TempoEvent(long tick, double bpm, int beatUnitDivisions) {
            this.tickAbsolute      = tick;
            this.bpm               = bpm;
            this.beatUnitDivisions = beatUnitDivisions;
        }
    }

    // ── Resultado público ────────────────────────────────────────────────────
    public static class ParseResult {
        public List<MusicNote>       allNotes;
        public double                totalDuration;
        public List<List<MusicNote>> voices;
        public List<String>          voiceNames;

        public ParseResult(List<MusicNote> allNotes, double totalDuration,
                           List<List<MusicNote>> voices, List<String> voiceNames) {
            this.allNotes      = allNotes;
            this.totalDuration = totalDuration;
            this.voices        = voices;
            this.voiceNames    = voiceNames;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PONTO DE ENTRADA
    // ═════════════════════════════════════════════════════════════════════════
    public static ParseResult parse(String filepath) throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setValidating(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(filepath));
        doc.getDocumentElement().normalize();

        // ── 1. Nomes das partes em <part-list> ───────────────────────────────
        Map<String, String> partIdToName = new LinkedHashMap<>();
        NodeList scoreParts = doc.getElementsByTagName("score-part");
        for (int i = 0; i < scoreParts.getLength(); i++) {
            Element sp   = (Element) scoreParts.item(i);
            String  id   = sp.getAttribute("id");
            String  name = textOf(sp, "part-name");
            if (name.isEmpty()) name = textOf(sp, "part-abbreviation");
            if (name.isEmpty()) name = id;
            partIdToName.put(id, name);
        }

        NodeList parts = doc.getElementsByTagName("part");

        // ── 2. PASSO A: constrói o mapa global de andamentos a partir da P1 ──
        //    (ou da primeira parte que contiver <metronome>)
        List<TempoEvent> globalTempoMap = buildTempoMap(parts);

        // ── 3. PASSO B: parseia cada parte usando o mapa global ───────────────
        List<List<MusicNote>> allVoices  = new ArrayList<>();
        List<String>          voiceNames = new ArrayList<>();
        double maxDuration = 0.0;

        for (int p = 0; p < parts.getLength(); p++) {
            Element part   = (Element) parts.item(p);
            String  partId = part.getAttribute("id");

            List<MusicNote> voiceNotes = parsePartWithTempoMap(part, globalTempoMap);

            if (!voiceNotes.isEmpty()) {
                allVoices.add(voiceNotes);

                String resolvedName = partIdToName.getOrDefault(partId, "");
                if (resolvedName.isEmpty())
                    resolvedName = "Voz " + allVoices.size();
                voiceNames.add(resolvedName);

                double lastEnd = voiceNotes.get(voiceNotes.size() - 1).endTime;
                if (lastEnd > maxDuration) maxDuration = lastEnd;
            }
        }

        List<MusicNote> allNotes = new ArrayList<>();
        for (List<MusicNote> voice : allVoices) allNotes.addAll(voice);
        allNotes.sort(Comparator.comparingDouble(n -> n.startTime));

        return new ParseResult(allNotes, maxDuration, allVoices, voiceNames);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PASSO A — buildTempoMap
    //  Percorre TODAS as partes em busca de <metronome><per-minute>.
    //  Usa o tick absoluto de cada evento para montar a linha do tempo.
    //  Isso funciona porque todas as partes compartilham o mesmo andamento.
    // ═════════════════════════════════════════════════════════════════════════
    private static List<TempoEvent> buildTempoMap(NodeList parts) {

        List<TempoEvent> tempoMap = new ArrayList<>();

        // divisions pode variar por parte; usamos a da P1 (índice 0) como base
        // mas relemos a cada measure corretamente.
        int divisions = 1;

        // Vamos varrer a P1 (primeira parte). Se não tiver metrônomo,
        // tentamos as outras — mas o Sibelius sempre coloca na P1.
        Element p1 = (Element) parts.item(0);
        long tickAbs = 0; // cursor de ticks absolutos

        NodeList measures = p1.getElementsByTagName("measure");
        for (int m = 0; m < measures.getLength(); m++) {
            Element measure = (Element) measures.item(m);

            // ── Processa filhos diretos em ordem ──────────────────────────────
            NodeList children = measure.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                Node child = children.item(c);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) child;

                switch (el.getTagName()) {

                    case "attributes": {
                        // Lê <divisions> como filho direto de <attributes>
                        NodeList ac = el.getChildNodes();
                        for (int i = 0; i < ac.getLength(); i++) {
                            Node n = ac.item(i);
                            if (n.getNodeType() == Node.ELEMENT_NODE
                                    && n.getNodeName().equals("divisions")) {
                                divisions = Integer.parseInt(
                                        n.getTextContent().trim());
                            }
                        }
                        break;
                    }

                    case "direction": {
                        // Procura <metronome> dentro de <direction>
                        NodeList metronomes = el.getElementsByTagName("metronome");
                        if (metronomes.getLength() > 0) {
                            Element metro = (Element) metronomes.item(0);
                            double bpm = parsePerMinute(metro);
                            if (bpm > 0) {
                                int beatUnitDivs = parseBeatUnitDivisions(metro, divisions);
                                tempoMap.add(new TempoEvent(tickAbs, bpm, beatUnitDivs));
                            }
                        }
                        break;
                    }

                    case "note": {
                        // Avança o tick absoluto (acorde não avança)
                        boolean isChord = el.getElementsByTagName("chord").getLength() > 0;
                        if (!isChord) {
                            int dur = parseDurationChild(el);
                            tickAbs += dur;
                        }
                        break;
                    }

                    case "backup": {
                        int dur = parseDurationChild(el);
                        tickAbs -= dur;
                        if (tickAbs < 0) tickAbs = 0;
                        break;
                    }

                    case "forward": {
                        int dur = parseDurationChild(el);
                        tickAbs += dur;
                        break;
                    }

                    default:
                        break;
                }
            }
        }

        // Garante ao menos um evento de andamento padrão (120 BPM)
        if (tempoMap.isEmpty()) {
            tempoMap.add(new TempoEvent(0, 120.0, divisions));
        }

        // Ordena por tick (geralmente já está em ordem, mas por segurança)
        tempoMap.sort(Comparator.comparingLong(e -> e.tickAbsolute));

        return tempoMap;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PASSO B — parsePartWithTempoMap
    //  Parseia uma parte convertendo ticks → segundos usando o mapa global.
    // ═════════════════════════════════════════════════════════════════════════
    private static List<MusicNote> parsePartWithTempoMap(
            Element part, List<TempoEvent> tempoMap) {

        List<MusicNote> voiceNotes = new ArrayList<>();
        int  divisions = 1;
        long tickAbs   = 0; // cursor de ticks absolutos desta parte

        NodeList measures = part.getElementsByTagName("measure");
        for (int m = 0; m < measures.getLength(); m++) {
            Element  measure  = (Element) measures.item(m);
            NodeList children = measure.getChildNodes();

            for (int c = 0; c < children.getLength(); c++) {
                Node child = children.item(c);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) child;

                switch (el.getTagName()) {

                    case "attributes": {
                        NodeList ac = el.getChildNodes();
                        for (int i = 0; i < ac.getLength(); i++) {
                            Node n = ac.item(i);
                            if (n.getNodeType() == Node.ELEMENT_NODE
                                    && n.getNodeName().equals("divisions")) {
                                divisions = Integer.parseInt(
                                        n.getTextContent().trim());
                            }
                        }
                        break;
                    }

                    case "backup": {
                        int dur = parseDurationChild(el);
                        tickAbs -= dur;
                        if (tickAbs < 0) tickAbs = 0;
                        break;
                    }

                    case "forward": {
                        int dur = parseDurationChild(el);
                        tickAbs += dur;
                        break;
                    }

                    case "note": {
                        tickAbs = processNoteWithTempoMap(
                                el, divisions, tempoMap, tickAbs, voiceNotes);
                        break;
                    }

                    // <direction>, <print>, <barline> etc. → ignorados aqui
                    // (o andamento já foi extraído no buildTempoMap)
                    default:
                        break;
                }
            }
        }

        return voiceNotes;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  processNoteWithTempoMap
    //  Converte a nota usando o mapa global de andamentos.
    //  Retorna o novo tickAbs.
    // ═════════════════════════════════════════════════════════════════════════
    private static long processNoteWithTempoMap(
            Element note, int divisions,
            List<TempoEvent> tempoMap,
            long tickAbs, List<MusicNote> voiceNotes) {

        int durDivisions = parseDurationChild(note);
        boolean isChord  = note.getElementsByTagName("chord").getLength() > 0;

        // Converte tick de início → segundos
        double startSeconds = ticksToSeconds(tickAbs, tempoMap, divisions);

        // Converte tick de fim → segundos (leva em conta mudança de tempo no meio)
        long   endTick      = isChord ? tickAbs : tickAbs + durDivisions;
        double endSeconds   = ticksToSeconds(endTick, tempoMap, divisions);
        double durationSecs = endSeconds - startSeconds;
        if (durationSecs < 0) durationSecs = 0;

        // ── É rest? ───────────────────────────────────────────────────────────
        if (note.getElementsByTagName("rest").getLength() > 0) {
            return isChord ? tickAbs : tickAbs + durDivisions;
        }

        // ── Precisa ter pitch ─────────────────────────────────────────────────
        NodeList pitchList = note.getElementsByTagName("pitch");
        if (pitchList.getLength() == 0) {
            return isChord ? tickAbs : tickAbs + durDivisions;
        }
        Element pitchElem = (Element) pitchList.item(0);

        String step   = pitchElem.getElementsByTagName("step").item(0)
                .getTextContent().trim();
        int    octave = Integer.parseInt(
                pitchElem.getElementsByTagName("octave").item(0)
                        .getTextContent().trim());
        int    alter  = 0;
        NodeList alterList = pitchElem.getElementsByTagName("alter");
        if (alterList.getLength() > 0)
            alter = (int) Math.round(Double.parseDouble(
                    alterList.item(0).getTextContent().trim()));

        String pitchName = step
                + (alter == 1 ? "#" : alter == -1 ? "b" : "")
                + octave;

        Map<String, Integer> noteValues = new HashMap<>();
        noteValues.put("C", 0); noteValues.put("D", 2); noteValues.put("E", 4);
        noteValues.put("F", 5); noteValues.put("G", 7); noteValues.put("A", 9);
        noteValues.put("B", 11);
        int midi = (octave + 1) * 12 + noteValues.get(step) + alter;

        // ── Letra: somente de <lyric><text> ──────────────────────────────────
        String lyric = "";
        NodeList lyricNodes = note.getElementsByTagName("lyric");
        if (lyricNodes.getLength() > 0) {
            Element lyricEl   = (Element) lyricNodes.item(0);
            NodeList textNodes = lyricEl.getElementsByTagName("text");
            if (textNodes.getLength() > 0)
                lyric = textNodes.item(0).getTextContent().trim();
        }

        MusicNote musicNote = new MusicNote(
                pitchName, midi, durationSecs, startSeconds, lyric);
        voiceNotes.add(musicNote);

        return isChord ? tickAbs : tickAbs + durDivisions;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ticksToSeconds
    //  Converte uma posição absoluta em ticks para segundos,
    //  integrando pelo mapa de andamentos (trata mudanças no meio da nota).
    // ═════════════════════════════════════════════════════════════════════════
    private static double ticksToSeconds(
            long tick, List<TempoEvent> tempoMap, int divisions) {

        if (tempoMap.isEmpty()) {
            // fallback: 120 BPM, beat-unit = quarter (= divisions ticks)
            return (tick / (double) divisions) * (60.0 / 120.0);
        }

        double seconds  = 0.0;
        long   remaining = tick;

        for (int i = 0; i < tempoMap.size(); i++) {
            TempoEvent current = tempoMap.get(i);
            long nextTick = (i + 1 < tempoMap.size())
                    ? tempoMap.get(i + 1).tickAbsolute
                    : Long.MAX_VALUE;

            if (remaining <= 0) break;

            // Quantos ticks desta seção estão dentro do nosso alvo?
            long ticksInSection = Math.min(remaining,
                    nextTick - current.tickAbsolute);
            if (ticksInSection <= 0) continue;

            // Converte ticks → beats da nota de referência → segundos
            // beatUnitDivisions é quantos ticks tem a nota de referência
            // (já em ticks do arquivo, não precisa reescalar por divisions)
            double beats   = ticksInSection / (double) current.beatUnitDivisions;
            double secPerBeat = 60.0 / current.bpm;
            seconds  += beats * secPerBeat;
            remaining -= ticksInSection;
        }

        // Se restaram ticks além do último evento (música mais longa que o mapa)
        if (remaining > 0) {
            TempoEvent last = tempoMap.get(tempoMap.size() - 1);
            double beats      = remaining / (double) last.beatUnitDivisions;
            seconds += beats * (60.0 / last.bpm);
        }

        return seconds;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers de leitura do <metronome>
    // ─────────────────────────────────────────────────────────────────────────

    // Lê <per-minute> de dentro de <metronome>
    private static double parsePerMinute(Element metronome) {
        NodeList nl = metronome.getElementsByTagName("per-minute");
        if (nl.getLength() == 0) return 0;
        try {
            return Double.parseDouble(nl.item(0).getTextContent().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Converte <beat-unit> para número de "divisions" (ticks)
    // quarter → divisions, half → divisions*2, eighth → divisions/2, etc.
    private static int parseBeatUnitDivisions(Element metronome, int divisions) {
        NodeList nl = metronome.getElementsByTagName("beat-unit");
        if (nl.getLength() == 0) return divisions; // padrão: quarter

        String beatUnit = nl.item(0).getTextContent().trim().toLowerCase();

        // Verifica se há <beat-unit-dot> (aumenta 50%)
        boolean dot = metronome.getElementsByTagName("beat-unit-dot").getLength() > 0;

        int baseTicks;
        switch (beatUnit) {
            case "64th":     baseTicks = Math.max(1, divisions / 16); break;
            case "32nd":     baseTicks = Math.max(1, divisions / 8);  break;
            case "16th":     baseTicks = Math.max(1, divisions / 4);  break;
            case "eighth":   baseTicks = Math.max(1, divisions / 2);  break;
            case "quarter":  baseTicks = divisions;                    break;
            case "half":     baseTicks = divisions * 2;                break;
            case "whole":    baseTicks = divisions * 4;                break;
            case "breve":    baseTicks = divisions * 8;                break;
            default:         baseTicks = divisions;                    break; // quarter como fallback
        }

        // Ponto de aumento: nota pontuada vale 1.5x
        if (dot) baseTicks = baseTicks + baseTicks / 2;

        return baseTicks;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lê <duration> como filho DIRETO de um elemento
    // ─────────────────────────────────────────────────────────────────────────
    private static int parseDurationChild(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && child.getNodeName().equals("duration")) {
                try {
                    return Integer.parseInt(child.getTextContent().trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helper: texto do primeiro filho com a tag dada
    // ─────────────────────────────────────────────────────────────────────────
    private static String textOf(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }
}