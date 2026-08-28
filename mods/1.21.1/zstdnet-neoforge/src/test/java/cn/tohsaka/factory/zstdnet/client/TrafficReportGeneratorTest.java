package cn.tohsaka.factory.zstdnet.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficReportGeneratorTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesSelfContainedLatestAndArchiveReports() throws Exception {
        String json = "{\"schemaVersion\":1,\"range\":\"today\",\"server\":{},\"selected\":{},\"today\":{},\"session\":{},\"current\":{},\"series\":[],\"daily\":[],\"runs\":[]}";

        TrafficReportGenerator.GeneratedReport generated = TrafficReportGenerator.generate(tempDir, json);

        assertTrue(Files.isRegularFile(generated.latest()));
        assertTrue(Files.isRegularFile(generated.archive()));
        String html = Files.readString(generated.latest(), StandardCharsets.UTF_8);
        assertTrue(html.contains("ZstdNet Analytics"));
        assertTrue(html.contains("\"schemaVersion\":1"));
        assertTrue(html.contains("data-lang=\"zh\""));
        assertTrue(html.contains("data-lang=\"en\""));
        assertTrue(html.contains("id=\"languageControl\" data-active=\"zh\""));
        assertTrue(html.contains(".segmented[data-active=\"en\"]::before { transform: translateX(100%); }"));
        assertTrue(html.contains("$('languageControl').dataset.active = language"));
        assertTrue(html.contains("button.setAttribute('aria-pressed', active ? 'true' : 'false')"));
        assertTrue(html.contains("id=\"themeToggle\""));
        assertTrue(html.contains("id=\"accentToggle\""));
        assertTrue(html.contains("class=\"mode-toggle\""));
        assertTrue(html.contains("@keyframes accent-top-bubbles"));
        assertTrue(html.contains("@keyframes accent-bottom-bubbles"));
        assertTrue(html.contains("zstdnet-report-accent"));
        assertTrue(html.contains("event.currentTarget.checked ? 'dark' : 'light'"));
        assertTrue(html.contains("id=\"serverTimezone\""));
        assertTrue(html.contains("服务器时区："));
        assertTrue(html.contains("data-window=\"1h\""));
        assertTrue(html.contains("data-window=\"custom\""));
        assertTrue(html.contains("id=\"customStart\""));
        assertTrue(html.contains("id=\"dailyTooltip\""));
        assertTrue(html.contains("id=\"zstdnetDownload\""));
        assertTrue(html.contains("https://www.curseforge.com/minecraft/mc-mods/zstdnet"));
        assertTrue(html.contains("data:image/png;base64,"));
        assertTrue(html.contains("rel=\"icon\" type=\"image/png\" href=\"data:image/png;base64,"));
        assertTrue(html.contains("data:image/svg+xml;base64,"));
        assertTrue(html.contains("id=\"rateExplanation\""));
        assertTrue(html.contains("id=\"bandwidthContext\""));
        assertTrue(html.contains("id=\"directionShare\""));
        assertTrue(html.contains("data-direction=\"egress\""));
        assertTrue(html.contains("data-direction=\"ingress\""));
        assertTrue(html.contains("@keyframes donut-ignite"));
        assertTrue(html.contains("@keyframes donut-core-light"));
        assertTrue(html.contains("backdrop-filter: blur(18px) saturate(125%)"));
        assertFalse(html.contains("@keyframes glass-materialize"));
        assertFalse(html.contains(".card:hover, .panel:hover"));
        assertFalse(html.contains(".card:hover::before, .panel:hover::before"));
        assertTrue(html.contains("animateDirectionValue(directionModel.total, true, 900)"));
        assertTrue(html.contains("function animateDaily()"));
        assertTrue(html.contains("dailyReveal-delay"));
        assertTrue(html.contains("const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches"));
        assertTrue(html.contains("fitDonutValue"));
        assertTrue(html.contains("--donut-value-size"));
        assertTrue(html.contains("elapsedSeconds += Math.max(0, Number(point.durationSeconds) || 0)"));
        assertFalse(html.contains("const elapsedSeconds = Math.max(1, (end - start) / 1000)"));
        assertTrue(html.contains("95% 时间内，入站不超过"));
        assertFalse(html.contains("聚合点"));
        assertFalse(html.contains("服务端视角"));
        assertTrue(html.contains("Ingress stayed below this 95% of the time"));
        assertFalse(html.contains("/*__ZSTDNET_REPORT_JSON__*/"));
        assertFalse(html.contains("/*__ZSTDNET_LOGO__*/"));
        assertFalse(html.contains("/*__CURSEFORGE_ICON__*/"));
        assertFalse(html.contains("不会开启 HTTP 端口或上传数据"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("<link rel=\"stylesheet\""));
        assertEquals(html, Files.readString(generated.archive(), StandardCharsets.UTF_8));
    }

    @Test
    void centersDailyLabelsAndDeterminesTheirSpacingFromAvailableWidth() throws Exception {
        TrafficReportGenerator.GeneratedReport generated = TrafficReportGenerator.generate(
            tempDir,
            "{\"schemaVersion\":1,\"daily\":[]}"
        );

        String html = Files.readString(generated.latest(), StandardCharsets.UTF_8);
        assertTrue(html.contains("const labelStep=Math.max(1,Math.ceil((widestDateLabel+12)/slot))"));
        assertTrue(html.contains("ctx.fillStyle=cssColor('--chart-label');ctx.textAlign='center'"));
        assertFalse(html.contains("const labels=Math.min(7,points.length)"));
    }

    @Test
    void keepsTenArchivedReportsPerServer() throws Exception {
        String json = "{\"schemaVersion\":1}";
        TrafficReportGenerator.GeneratedReport firstServer = TrafficReportGenerator.generate(
            tempDir,
            "play.example.com:25565",
            json
        );
        TrafficReportGenerator.GeneratedReport secondServer = TrafficReportGenerator.generate(
            tempDir,
            "backup.example.com:25565",
            json
        );

        assertFalse(firstServer.archive().getParent().equals(secondServer.archive().getParent()));
        for (int i = 0; i < 12; i++) {
            Files.writeString(
                firstServer.archive().getParent().resolve(String.format("zstdnet-report-20200101-0000%02d.html", i)),
                "old"
            );
        }

        TrafficReportGenerator.GeneratedReport newest = TrafficReportGenerator.generate(
            tempDir,
            "play.example.com:25565",
            json
        );

        assertEquals(10L, countArchives(newest.archive().getParent()));
        assertEquals(1L, countArchives(secondServer.archive().getParent()));
        assertTrue(Files.isRegularFile(newest.latest()));
        assertTrue(Files.isRegularFile(secondServer.latest()));
        assertFalse(Files.exists(newest.archive().getParent().resolve("zstdnet-report-20200101-000000.html")));
    }

    @Test
    void embedsTheClientConnectionAddressWithoutReplacingTheServerListener() throws Exception {
        String json = "{\"schemaVersion\":1,\"server\":{\"listen\":\"0.0.0.0:25565\"}}";

        TrafficReportGenerator.GeneratedReport generated = TrafficReportGenerator.generate(
            tempDir,
            "cdr4.齿轮盛宴.com",
            json
        );

        String html = Files.readString(generated.latest(), StandardCharsets.UTF_8);
        assertTrue(html.contains("\"connectedAddress\":\"cdr4.齿轮盛宴.com\""));
        assertTrue(html.contains("\"listen\":\"0.0.0.0:25565\""));
        assertTrue(html.contains("server.connectedAddress || server.listen"));
    }

    private static long countArchives(Path reportsDir) throws Exception {
        try (Stream<Path> files = Files.list(reportsDir)) {
            return files
                .filter(path -> path.getFileName().toString().startsWith("zstdnet-report-"))
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .count();
        }
    }
}
