package dev.portfolio.aftersales;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private final Store store;
    private final AgentService agent;

    AnalysisWorker(Store store, AgentService agent) {
        this.store = store;
        this.agent = agent;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 3_000)
    void process() {
        store.resetStaleTasks();
        for (AnalysisTask task : store.readyTasks()) {
            if (!store.claimTask(task.id())) continue;
            try {
                AgentResult result = agent.analyze(task.caseId());
                store.saveAnalysis(task.caseId(), result);
                store.taskDone(task.id());
            } catch (Exception exception) {
                log.warn("Analysis failed for case {}: {}", task.caseId(), exception.getMessage());
                store.taskFailed(task.id(), task.attempts() + 1, exception.toString());
            }
        }
    }
}
