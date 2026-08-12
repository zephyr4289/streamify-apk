#include "EventTracker.h"
#include "StreamifyDB.h"
#include <iostream>

EventTracker& EventTracker::getInstance() {
    static EventTracker instance;
    return instance;
}

void EventTracker::logPlay(int fromTrackId, int toTrackId, int userId) {
    if (fromTrackId <= 0 || toTrackId <= 0) return;
    
    // In a full implementation, we'd add insertTransition to StreamifyDB:
    // db.insertTransition(userId, fromTrackId, toTrackId);
    std::cout << "[EventTracker] Logged PLAY transition from " << fromTrackId << " to " << toTrackId << std::endl;
}

void EventTracker::logSkip(int fromTrackId, int toTrackId, int userId) {
    if (fromTrackId <= 0 || toTrackId <= 0) return;
    
    // In a full implementation, we'd add insertSkip to StreamifyDB:
    // db.insertSkip(userId, fromTrackId, toTrackId);
    std::cout << "[EventTracker] Logged SKIP transition from " << fromTrackId << " to " << toTrackId << std::endl;
}
