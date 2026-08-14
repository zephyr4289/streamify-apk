#ifndef STREAMIFY_TASK_ORCHESTRATOR_H
#define STREAMIFY_TASK_ORCHESTRATOR_H

#include <string>
#include <atomic>
#include <mutex>
#include <chrono>

struct OrchestratorMetrics {
    std::string state;
    std::string currentAction;
    int activeAiTasks;
    int completedAiTasks;
    int totalAiTasks;
    int cpuCoreBudget;
    int activeThreads;
    bool isThrottled;
};

class TaskOrchestrator {
public:
    static TaskOrchestrator& getInstance();

    void setHighPriorityActive(bool active);
    bool isHighPriorityActive() const;

    bool shouldYield();
    void cooperativeYield();

    void notifyAiTaskStarted(const std::string& trackName);
    void notifyAiTaskCompleted();
    void setTotalAiTasks(int total);

    OrchestratorMetrics getMetrics();
    int getOptimalWorkerThreads();

private:
    TaskOrchestrator();
    ~TaskOrchestrator() = default;

    std::atomic<bool> m_highPriorityActive{false};
    std::atomic<int> m_activeAiTasks{0};
    std::atomic<int> m_completedAiTasks{0};
    std::atomic<int> m_totalAiTasks{0};
    
    std::mutex m_statusMutex;
    std::string m_currentAction{"Idle"};
    std::chrono::steady_clock::time_point m_lastHighPriorityTime;
};

#endif // STREAMIFY_TASK_ORCHESTRATOR_H
