package io.mosip.liveness.eval;

import io.mosip.liveness.eval.Evaluator.CategoryMetrics;
import io.mosip.liveness.eval.Evaluator.PresentationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI runner for benchmarking ISO/IEC 30107-3 Presentation Attack Detection performance.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String genuineDir = null;
        String printDir = null;
        String replayDir = null;
        String outputPath = "liveness-eval-report.md";
        String modelPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--genuine".equalsIgnoreCase(arg) && i + 1 < args.length) {
                genuineDir = args[++i];
            } else if ("--print".equalsIgnoreCase(arg) && i + 1 < args.length) {
                printDir = args[++i];
            } else if ("--replay".equalsIgnoreCase(arg) && i + 1 < args.length) {
                replayDir = args[++i];
            } else if ("--out".equalsIgnoreCase(arg) && i + 1 < args.length) {
                outputPath = args[++i];
            } else if ("--model".equalsIgnoreCase(arg) && i + 1 < args.length) {
                modelPath = args[++i];
            } else if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printHelp();
                return;
            }
        }

        if (genuineDir == null && printDir == null && replayDir == null) {
            System.err.println("Error: At least one dataset directory (--genuine, --print, or --replay) must be provided.");
            printHelp();
            System.exit(1);
        }

        Evaluator evaluator = new Evaluator(modelPath);

        List<PresentationResult> genuineResults = genuineDir != null
                ? evaluator.evaluateCategory(new File(genuineDir), "GENUINE")
                : List.of();

        List<PresentationResult> printResults = printDir != null
                ? evaluator.evaluateCategory(new File(printDir), "PRINT_ATTACK")
                : List.of();

        List<PresentationResult> replayResults = replayDir != null
                ? evaluator.evaluateCategory(new File(replayDir), "REPLAY_ATTACK")
                : List.of();

        CategoryMetrics genuineMetrics = Evaluator.computeMetrics("Genuine (Bona Fide)", genuineResults, false);
        CategoryMetrics printMetrics = Evaluator.computeMetrics("Print Attack (PAI 1)", printResults, true);
        CategoryMetrics replayMetrics = Evaluator.computeMetrics("Screen Replay Attack (PAI 2)", replayResults, true);

        // Overall Attack Metrics
        List<PresentationResult> allAttacks = new ArrayList<>();
        allAttacks.addAll(printResults);
        allAttacks.addAll(replayResults);
        CategoryMetrics overallAttackMetrics = Evaluator.computeMetrics("Overall Attacks", allAttacks, true);

        String report = generateMarkdownReport(
                genuineMetrics,
                printMetrics,
                replayMetrics,
                overallAttackMetrics,
                genuineResults,
                printResults,
                replayResults,
                modelPath
        );

        System.out.println(report);

        try (FileWriter fw = new FileWriter(outputPath, StandardCharsets.UTF_8)) {
            fw.write(report);
            System.out.println("\nEvaluation report successfully saved to: " + outputPath);
        } catch (IOException e) {
            System.err.println("Failed to write report to " + outputPath + ": " + e.getMessage());
        }
    }

    private static String generateMarkdownReport(
            CategoryMetrics genuine,
            CategoryMetrics print,
            CategoryMetrics replay,
            CategoryMetrics overallAttack,
            List<PresentationResult> genuineList,
            List<PresentationResult> printList,
            List<PresentationResult> replayList,
            String modelPath
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MOSIP Biometric Face Liveness & PAD Evaluation Report\n\n");
        sb.append("**Evaluation Timestamp:** ").append(Instant.now()).append("\n");
        sb.append("**Engine Mode:** ").append(modelPath != null ? "Heuristic + ONNX (" + modelPath + ")" : "Pure Java Heuristic Baseline").append("\n");
        sb.append("**Target Standards:** ISO/IEC 30107-3 (APCER ≤ 1.0%, BPCER ≤ 3.0%)\n\n");

        sb.append("## 1. Summary of Presentation Attack Detection (PAD) Metrics\n\n");
        sb.append("| Presentation Category | Sample Count | Accepted | Rejected / Error | Metric | Measured Rate | Target (CODING_STANDARDS) | Compliance Status |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n");

        // Genuine (BPCER)
        String bpcerStatus = genuine.totalPresentations() == 0 ? "N/A"
                : (genuine.errorRatePercent() <= 3.0 ? "PASS" : "FAIL");
        sb.append(String.format("| **Bona Fide (Genuine)** | %d | %d | %d | **BPCER** | %.2f%% | ≤ 3.00%% | %s |\n",
                genuine.totalPresentations(), genuine.acceptedCount(), genuine.rejectedCount() + genuine.retryOrInconclusiveCount(),
                genuine.errorRatePercent(), bpcerStatus));

        // Print Attack (APCER)
        if (print.totalPresentations() > 0) {
            String printStatus = print.errorRatePercent() <= 1.0 ? "PASS" : "FAIL";
            sb.append(String.format("| **Print Attack (PAI 1)** | %d | %d | %d | **APCER** | %.2f%% | ≤ 1.00%% | %s |\n",
                    print.totalPresentations(), print.acceptedCount(), print.rejectedCount(),
                    print.errorRatePercent(), printStatus));
        }

        // Replay Attack (APCER)
        if (replay.totalPresentations() > 0) {
            String replayStatus = replay.errorRatePercent() <= 1.0 ? "PASS" : "FAIL";
            sb.append(String.format("| **Screen Replay (PAI 2)** | %d | %d | %d | **APCER** | %.2f%% | ≤ 1.00%% | %s |\n",
                    replay.totalPresentations(), replay.acceptedCount(), replay.rejectedCount(),
                    replay.errorRatePercent(), replayStatus));
        }

        // Overall Attack APCER
        if (overallAttack.totalPresentations() > 0) {
            String overallStatus = overallAttack.errorRatePercent() <= 1.0 ? "PASS" : "FAIL";
            sb.append(String.format("| **Overall Presentation Attacks** | %d | %d | %d | **Overall APCER** | %.2f%% | ≤ 1.00%% | %s |\n",
                    overallAttack.totalPresentations(), overallAttack.acceptedCount(), overallAttack.rejectedCount(),
                    overallAttack.errorRatePercent(), overallStatus));
        }

        sb.append("\n");

        // Statistical Disclaimer & Honesty Mandate
        sb.append("## 2. Statistical Reliability & Integrity Statement\n\n");
        int totalPresentations = genuine.totalPresentations() + overallAttack.totalPresentations();
        if (totalPresentations < 1000) {
            sb.append("> [!WARNING]\n");
            sb.append("> **Statistically Limited Sample Size:** The current evaluation set comprises **")
                    .append(totalPresentations).append("** total presentations. ")
                    .append("Per ISO/IEC 30107-3 guidance, statistically significant certification requires hundreds to thousands of diverse presentations ")
                    .append("across lighting, demographics, devices, and attack media. ")
                    .append("Results above serve as a baseline heuristic demonstration and do NOT constitute certified ISO 30107-3 compliance.\n\n");
        } else {
            sb.append("> [!NOTE]\n");
            sb.append("> Sample size of ").append(totalPresentations).append(" presentations meets large-scale statistical threshold.\n\n");
        }

        // Per presentation breakdown
        sb.append("## 3. Presentation Breakdown\n\n");
        sb.append("<details><summary>Click to view individual presentation logs (").append(totalPresentations).append(" total)</summary>\n\n");
        sb.append("| ID | Category | Frames | Final Decision | Score |\n");
        sb.append("| :--- | :--- | :---: | :---: | :---: |\n");

        List<PresentationResult> allResults = new ArrayList<>();
        allResults.addAll(genuineList);
        allResults.addAll(printList);
        allResults.addAll(replayList);

        for (PresentationResult r : allResults) {
            sb.append(String.format("| `%s` | %s | %d | **%s** | %.2f |\n",
                    r.presentationId(), r.category(), r.totalFrames(), r.finalDecision(), r.livenessScore()));
        }
        sb.append("\n</details>\n\n");

        sb.append("---\n*Generated by `io.mosip.liveness.eval`*\n");
        return sb.toString();
    }

    private static void printHelp() {
        System.out.println("MOSIP Face Liveness & PAD Evaluator CLI");
        System.out.println("Usage:");
        System.out.println("  java -jar liveness-eval.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --genuine <dir>   Directory of genuine bona fide presentation folders");
        System.out.println("  --print <dir>     Directory of printed photo presentation folders");
        System.out.println("  --replay <dir>    Directory of screen video replay presentation folders");
        System.out.println("  --out <file.md>   Markdown report destination (default: liveness-eval-report.md)");
        System.out.println("  --model <path>    Optional path to ONNX model");
        System.out.println("  -h, --help        Print this usage message");
    }
}
