#include "TaskOrchestrator.h"
#include <unistd.h>
#include <thread>
#include <algorithm>
#include <android/log.h>

TaskOrchestrator& TaskOrchestrator::getInstance() {
    static TaskOrchestrator instance;
    return instance;
}

TaskOrchestrator::TaskOrchestrator() {
    m_lastHighPriorityTime = std::chrono::steady_clock::now();
}

void TaskOrchestrator::setHighPriorityActive(bool active) {
    m_highPriorityActive.store(active, std::memory_order_release);
    if (active) {
        m_lastHighPriorityTime = std::chrono::steady_clock::now();
    }
}

bool TaskOrchestrator::isHighPriorityActive() const {
    if (m_highPriorityActive.load(std::memory_order_acquire)) {
        return true;
    }
    // Maintain a 1.5s high-priority cooldown after burst UI actions
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - m_lastHighPriorityTime
    ).count();
    return elapsed < 1500;
}

bool TaskOrchestrator::shouldYield() {
    return isHighPriorityActive();
}

void TaskOrchestrator::cooperativeYield() {
    if (shouldYield()) {
        // Sleep for 30ms to yield CPU timeslices completely to UI and playback threads
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
    } else {
        // Light yield to allow OS scheduler context switching
        std::this_thread::yield();
    }
}

void TaskOrchestrator::notifyAiTaskStarted(const std::string& trackName) {
    m_activeAiTasks.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(m_statusMutex);
    m_currentAction = "Processing: " + trackName;
}

void TaskOrchestrator::notifyAiTaskCompleted() {
    m_activeAiTasks.fetch_sub(1, std::memory_order_relaxed);
    m_completedAiTasks.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(m_statusMutex);
    if (m_activeAiTasks.load() <= 0) {
        m_currentAction = "Idle (Neural Store Synchronized)";
    }
}

void TaskOrchestrator::setTotalAiTasks(int total) {
    m_totalAiTasks.store(total, std::memory_order_relaxed);
}

int TaskOrchestrator::getOptimalWorkerThreads() {
    long numCores = sysconf(_SC_NPROCESSORS_ONLN);
    if (numCores <= 0) {
        numCores = std::thread::hardware_concurrency();
    }
    if (numCores <= 2) return 1;
    if (numCores <= 4) return 1;
    // On 8-core Big.LITTLE architectures, reserve at most 2 cores for AI ingestion
    return std::min(2, static_cast<int>(numCores / 4));
}

OrchestratorMetrics TaskOrchestrator::getMetrics() {
    OrchestratorMetrics m;
    long numCores = sysconf(_SC_NPROCESSORS_ONLN);
    if (numCores <= 0) numCores = 4;

    m.activeAiTasks = m_activeAiTasks.load(std::memory_order_relaxed);
    m.completedAiTasks = m_completedAiTasks.load(std::memory_order_relaxed);
    m.totalAiTasks = m_totalAiTasks.load(std::memory_order_relaxed);
    m.isThrottled = isHighPriorityActive();
    m.cpuCoreBudget = m.isThrottled ? 25 : 60; // 25% max when UI/Search active, 60% idle
    m.activeThreads = m.activeAiTasks > 0 ? getOptimalWorkerThreads() : 0;

    {
        std::lock_guard<std::mutex> lock(m_statusMutex);
        m.currentAction = m_currentAction;
    }

    if (m.activeAiTasks > 0) {
        if (m.isThrottled) {
            m.state = "THROTTLED_UI_PRIORITY";
        } else {
            m.state = "AI_EMBEDDING_ACTIVE";
        }
    } else {
        m.state = "IDLE";
        m.currentAction = "Idle (Low Power Mode)";
    }

    return m;
}
