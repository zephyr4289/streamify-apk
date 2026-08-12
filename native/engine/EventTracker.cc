#include "EventTracker.h"
#include "StreamifyDB.h"
#include <iostream>

EventTracker& EventTracker::getInstance() {
    static EventTracker instance;
    return instance;
}

void EventTracker::logPlay(int fromTrackId, int toTrackId, int userId) {
    if (fromTrackId <= 0 || toTrackId <= 0) return;
    StreamifyDB::getInstance().insertTransition(userId, fromTrackId, toTrackId, "play");
    std::cout << "[EventTracker] Logged PLAY transition from " << fromTrackId << " to " << toTrackId << std::endl;
}

void EventTracker::logSkip(int fromTrackId, int toTrackId, int userId) {
    if (fromTrackId <= 0 || toTrackId <= 0) return;
    StreamifyDB::getInstance().insertTransition(userId, fromTrackId, toTrackId, "skip");
    std::cout << "[EventTracker] Logged SKIP transition from " << fromTrackId << " to " << toTrackId << std::endl;
}
