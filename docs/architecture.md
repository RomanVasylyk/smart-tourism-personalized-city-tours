# Initial architecture

## Data flow
1. `pipeline/` imports and prepares city POI data
2. `backend/` serves POI metadata and generates routes
3. `android-app/` requests route proposals and renders the result on the device

## First MVP
- One pilot city
- Walking route only
- Time budget + categories
- Simple heuristic planner
- Android screen with preferences and route result
