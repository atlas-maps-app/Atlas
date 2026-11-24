/* Atlas's public transport planner, as C sees it. The other side is ../src/lib.rs (Rust), built as a static library;
 * a test there holds every number below equal to its own.
 *
 * Nothing but numbers crosses: a query is an array of 32-bit numbers, an answer is one, and a name is the file's own
 * bytes of UTF-8 with their length. There is no structure whose layout the two sides could see differently.
 *
 * What a caller owes:
 * - an array is as long as its length says. An array of no numbers is the empty array, whatever its pointer (null
 *   too). A plan sent an array that has numbers and cannot be read (a null pointer with a length above 0, a pointer
 *   that is not aligned for a number, a length no memory holds) is refused, whichever array it is: JNI's
 *   GetIntArrayElements gives null when memory is short, and whoever hands that on with the array's length gets no
 *   answer, never the answer to a query without its wishes;
 * - the file's bytes stay where they are, unchanged, from atlas_transit_open until atlas_transit_close;
 * - atlas_transit_close comes last: after every other call on that timetable has returned, on every thread, and
 *   with none begun later. The library cannot hold to this for the caller (a call that meets a close reads memory
 *   that is gone): whoever closes holds the lock under which every plan, answer and close of that timetable is
 *   made.
 *
 * Threads otherwise: a timetable's numbers and names (atlas_transit_number, _text, _meta) may be read from any
 * thread at any time while it is open, during a plan too. One plan at a time on a timetable, and its answer read
 * before the next plan: here the library holds to it itself. While a plan is under way, a second atlas_transit_plan
 * on that timetable is refused (0), and so are atlas_transit_answer (0: the answer is not there yet) and
 * atlas_transit_trim (0: the memory is in use); none of them touches anything. Two timetables know nothing of each
 * other.
 *
 * What it gets: a query that cannot be read is refused whole (atlas_transit_plan gives 0) and never planned without
 * the part that was not understood; a number out of range names nothing (ATLAS_TRANSIT_NONE, or no bytes); a null
 * timetable answers nothing. Memory comes from malloc; where the planner finds itself wrong, it aborts.
 *
 * The library is built for 64-bit targets alone (arm64-v8a and x86_64 on Android): it takes malloc's memory as
 * aligned to 16 bytes, which a 32-bit C library does not give, and does not compile for one.
 *
 * Times are seconds of a service day as the file has them (../FORMAT.md, "Time"): a day is counted from the file's
 * first day, and a time may pass 86,400 or lie before 0. Places on the map are in 1e-7 degree. */
#ifndef ATLAS_TRANSIT_H
#define ATLAS_TRANSIT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* The interface's version: atlas_transit_abi() gives the library's, and the two must be equal. */
#define ATLAS_TRANSIT_ABI 2
/* Nothing: no stop, no station, no colour, no such record. */
#define ATLAS_TRANSIT_NONE (-1)
/* No day: what ATLAS_TRANSIT_N_DAY_OF gives for a number that is no date. */
#define ATLAS_TRANSIT_NO_DAY (-2147483647 - 1)

/* An open timetable. */
typedef struct atlas_transit atlas_transit;

uint32_t atlas_transit_abi(void);

/* Why a file was not opened (atlas_transit_open's `why`). */
#define ATLAS_TRANSIT_OPENED 0
#define ATLAS_TRANSIT_NOT_A_TIMETABLE 1 /* not a timetable file, or cut short before its table */
#define ATLAS_TRANSIT_BAD_VERSION 2     /* a version this library does not read */
#define ATLAS_TRANSIT_BAD_SECTION 3     /* a section missing, there twice, outside the file, or of the wrong length */
#define ATLAS_TRANSIT_BAD_RECORD 4      /* a number that points outside what it names, or passes a bound */
#define ATLAS_TRANSIT_BAD_DAYS 5        /* the file does not say which days it covers */
#define ATLAS_TRANSIT_INDEX_TOO_LARGE 6 /* a timetable, but what the planner works out of it would pass index_most */

/* Opens the timetable in `len` bytes at `bytes` (a file read or mapped whole): its checks are made, and what the
 * planner needs of it is worked out once (a moment, and memory, for a country's file: not on the thread that
 * draws). That memory, the planner's index, takes 4 to 6 MB for a country; how many bytes it takes is known from
 * the file before any is asked for, and over `index_most` bytes (0 or less: the library's own bound, 256 MB, which
 * no timetable comes near) the file is not opened, so that a file that asks for more than the phone has cannot
 * end the process. Null where the bytes are no timetable this library reads, or over that bound; `why`, unless
 * null, gets the reason, or ATLAS_TRANSIT_OPENED. The checks hold a file to its own form, not to its maker: a
 * fetched file is held to its size and hash before it comes here. */
atlas_transit *atlas_transit_open(const uint8_t *bytes, size_t len, int32_t index_most, int32_t *why);

/* Closes it; the bytes are the caller's again. Null closes nothing. After every other call on it has returned (see
 * the head of this file): never during a plan, an answer or a name being read. */
void atlas_transit_close(atlas_transit *t);

/* A number of the timetable: `what` says which; `id` is the stop, line, route or trip; `at` the place along a route
 * (0 for its first stop), where one is asked for. ATLAS_TRANSIT_NONE where there is none: no such stop, line, route
 * or trip, no such place along it, no such `what`, no timetable. Three numbers have -1 as a value of their own:
 * ATLAS_TRANSIT_N_DAY_OF gives ATLAS_TRANSIT_NO_DAY for nothing (for no timetable too); and for a stop -1 is a
 * latitude and a longitude like any other (1e-7 degree south of the equator, west of Greenwich), so whether there is
 * such a stop is told by its number (below ATLAS_TRANSIT_N_STOPS) or by ATLAS_TRANSIT_N_STOP_PLACE, never by these
 * two. */
int32_t atlas_transit_number(const atlas_transit *t, uint32_t what, uint32_t id, uint32_t at);

#define ATLAS_TRANSIT_N_FIRST_DAY 0      /* the file's first day, as YYYYMMDD: day 0 */
#define ATLAS_TRANSIT_N_DAYS 1           /* how many days it covers */
#define ATLAS_TRANSIT_N_STOPS 2          /* how many stops, lines, operators, routes, trips: each is named by a */
#define ATLAS_TRANSIT_N_LINES 3          /* number below its count */
#define ATLAS_TRANSIT_N_OPERATORS 4
#define ATLAS_TRANSIT_N_ROUTES 5
#define ATLAS_TRANSIT_N_TRIPS 6
#define ATLAS_TRANSIT_N_CHANGE_SECONDS 7 /* what a change at one stop takes where the stop has no time of its own */
#define ATLAS_TRANSIT_N_DAY_OF 8         /* id: a date as YYYYMMDD; the day it is, which may lie outside the file's
                                            days, or ATLAS_TRANSIT_NO_DAY */
#define ATLAS_TRANSIT_N_DATE_OF 9        /* id: a day; its date as YYYYMMDD */
#define ATLAS_TRANSIT_N_STOP_LAT 16
#define ATLAS_TRANSIT_N_STOP_LON 17
#define ATLAS_TRANSIT_N_STOP_STATION 18    /* the station it is part of */
#define ATLAS_TRANSIT_N_STOP_KIND 19       /* GTFS location_type: 0 a stop or platform, 1 a station, 2 an entrance,
                                              3 a generic node, 4 a boarding area */
#define ATLAS_TRANSIT_N_STOP_WHEELCHAIR 20 /* GTFS wheelchair_boarding: 0 not said, 1 possible, 2 not possible */
#define ATLAS_TRANSIT_N_STOP_PLACE 21      /* whose place the latitude and longitude are: 0 its own, 1 its station's,
                                              2 nobody's (0, 0: the stop is nowhere) */
#define ATLAS_TRANSIT_N_LINE_OPERATOR 32
#define ATLAS_TRANSIT_N_LINE_TYPE 33        /* GTFS route_type */
#define ATLAS_TRANSIT_N_LINE_COLOUR 34      /* 0xRRGGBB, where the feed gives one */
#define ATLAS_TRANSIT_N_LINE_TEXT_COLOUR 35
#define ATLAS_TRANSIT_N_ROUTE_LINE 48
#define ATLAS_TRANSIT_N_ROUTE_STOPS 49     /* how many stops the route calls at */
#define ATLAS_TRANSIT_N_ROUTE_STOP 50      /* the stop at place `at` along it */
#define ATLAS_TRANSIT_N_ROUTE_DIRECTION 51 /* GTFS direction_id: 0 or 1 */
#define ATLAS_TRANSIT_N_TRIP_ROUTE 64
#define ATLAS_TRANSIT_N_TRIP_WHEELCHAIR 65 /* GTFS wheelchair_accessible: 0 not said, 1 yes, 2 no */
#define ATLAS_TRANSIT_N_TRIP_BICYCLES 66   /* GTFS bikes_allowed: the same */
#define ATLAS_TRANSIT_N_TRIP_ARRIVES 67    /* when it arrives at place `at` along its route, and when it leaves */
#define ATLAS_TRANSIT_N_TRIP_LEAVES 68     /* there: seconds of the trip's own service day */

/* A name of the timetable: `what` says which, `id` whose, `at` (for a headsign) from which place along the route
 * on. The file's own bytes of UTF-8, not ended by a zero: `*len`, unless `len` is null, gets how many. They stay
 * until the timetable is closed. Never null; no name is no bytes. */
const uint8_t *atlas_transit_text(const atlas_transit *t, uint32_t what, uint32_t id, uint32_t at, size_t *len);

#define ATLAS_TRANSIT_T_STOP_ID 0
#define ATLAS_TRANSIT_T_STOP_CODE 1
#define ATLAS_TRANSIT_T_STOP_NAME 2
#define ATLAS_TRANSIT_T_STOP_PLATFORM 3
#define ATLAS_TRANSIT_T_STOP_ZONE 4
#define ATLAS_TRANSIT_T_STOP_TIMEZONE 5
#define ATLAS_TRANSIT_T_STOP_DESCRIPTION 6
#define ATLAS_TRANSIT_T_STOP_IFOPT 7
#define ATLAS_TRANSIT_T_LINE_ID 16
#define ATLAS_TRANSIT_T_LINE_SHORT 17 /* "12", "IC" */
#define ATLAS_TRANSIT_T_LINE_LONG 18
#define ATLAS_TRANSIT_T_LINE_DESCRIPTION 19
#define ATLAS_TRANSIT_T_LINE_URL 20
#define ATLAS_TRANSIT_T_OPERATOR_ID 32
#define ATLAS_TRANSIT_T_OPERATOR_NAME 33
#define ATLAS_TRANSIT_T_OPERATOR_URL 34
#define ATLAS_TRANSIT_T_OPERATOR_TIMEZONE 35
#define ATLAS_TRANSIT_T_OPERATOR_PHONE 36
#define ATLAS_TRANSIT_T_OPERATOR_LANGUAGE 37
#define ATLAS_TRANSIT_T_TRIP_ID 48
#define ATLAS_TRANSIT_T_TRIP_REALTIME 49   /* the id live data names the trip by */
#define ATLAS_TRANSIT_T_TRIP_NUMBER 50     /* a train's number */
#define ATLAS_TRANSIT_T_TRIP_KIND 51       /* "Intercity" */
#define ATLAS_TRANSIT_T_TRIP_BLOCK 52
#define ATLAS_TRANSIT_T_ROUTE_HEADSIGN 64  /* id: a route; what its vehicles show from place `at` on */

/* A line of the file's description of itself (../FORMAT.md, META), by its key: "feed_version",
 * "feed_publisher_name". As atlas_transit_text gives a name; no bytes where the file has no such line, where the
 * key is not UTF-8, or where it is longer than 4,096 bytes. */
const uint8_t *atlas_transit_meta(const atlas_transit *t, const uint8_t *key, size_t key_len, size_t *len);

/* Plans. Gives how many numbers the answer has: it stays with the timetable until the next plan, and
 * atlas_transit_answer reads it. 0: the query could not be read, and nothing was planned; or another plan on this
 * timetable is under way, or its answer is being read, and nothing was touched.
 *
 * `query` holds the query's numbers, each at its place, ATLAS_TRANSIT_Q_NEEDED of them at least and
 * ATLAS_TRANSIT_Q_LEN at most (a longer query is refused: its sender reads another header than the library's):
 *   ATLAS_TRANSIT_Q_FROM, _Q_TO   three numbers each: what the place is, and two numbers of its own:
 *                                   ATLAS_TRANSIT_P_STOP     a stop, and the stops a walk away from it; then 0
 *                                   ATLAS_TRANSIT_P_STATION  a station: any of its stops; then 0
 *                                   ATLAS_TRANSIT_P_POINT    latitude, longitude: the stops within reach of it
 *                                   ATLAS_TRANSIT_P_WALKS    0, 0: the stops in `from_walks` (or `to_walks`)
 *   ATLAS_TRANSIT_Q_DAY           the service day
 *   ATLAS_TRANSIT_Q_TIME          seconds of that day: the earliest departure, or the latest arrival
 * and, each of which may be left out (a shorter array) or be negative, and is then the planner's own:
 *   ATLAS_TRANSIT_Q_WINDOW        how far the moment may move, in seconds; 0 asks for the one moment
 *   ATLAS_TRANSIT_Q_ARRIVE_BY     1: the time is the latest arrival
 *   ATLAS_TRANSIT_Q_MAX_RIDES     the most rides a journey may have (8; 16 at most)
 *   ATLAS_TRANSIT_Q_REACH_M       how far from a point a stop may be, in metres by the straight line (1000)
 *   ATLAS_TRANSIT_Q_WALK_MM_S     walking speed in mm/s (1250), over the straight line taken 30 % longer
 *   ATLAS_TRANSIT_Q_CHANGE_S      what a change at one stop takes, in seconds (the file's, a stop's own)
 *   ATLAS_TRANSIT_Q_RULES         0: the feed's rules between trips do not hold
 *   ATLAS_TRANSIT_Q_WEIGH         0: every option that no other beats in departure, arrival, rides and cost each;
 *                                 else a wish's seconds are what following it is worth
 *   ATLAS_TRANSIT_Q_OPTIONS       journeys returned (64)
 *   ATLAS_TRANSIT_Q_DEPARTURES    moments searched (2048)
 *   ATLAS_TRANSIT_Q_HORIZON       how long after the asked moment a journey may be under way, in seconds (86400)
 *   ATLAS_TRANSIT_Q_WHOLE         1: the search goes as far as the horizon at once (it gives the same, later)
 *   ATLAS_TRANSIT_Q_BAG, _Q_LABELS, _Q_STEPS_K   what one answer may cost (steps in thousands): see the
 *                                 planner's Limits. An answer that met one says it was cut short.
 *
 * `from_walks`, `to_walks`: for a place of kind ATLAS_TRANSIT_P_WALKS, pairs of a stop and the seconds on foot to
 * it (or from it), from whoever knows the streets.
 *
 * `wishes`: one after another, each ATLAS_TRANSIT_W_HEAD numbers (what, how, seconds, how many numbers follow) and
 * then those that follow. How: ATLAS_TRANSIT_H_NEVER (not at all; seconds 0), _H_AVOID (each use costs so many
 * seconds), _H_PREFER (each use of anything else does). What, and what follows:
 *   ATLAS_TRANSIT_W_STOP      a stop; 1 where riding past it counts too, else 0
 *   ATLAS_TRANSIT_W_STATION   a station; the same
 *   ATLAS_TRANSIT_W_LINE      a line
 *   ATLAS_TRANSIT_W_OPERATOR  an operator: every line of it
 *   ATLAS_TRANSIT_W_MODE      a GTFS route_type: every line of that kind of vehicle (3 a bus, 2 rail, 0 a tram)
 *   ATLAS_TRANSIT_W_TRIP      a trip
 *   ATLAS_TRANSIT_W_NO_WHEELCHAIR, _W_WHEELCHAIR_UNKNOWN, _W_NO_BICYCLES, _W_BICYCLES_UNKNOWN   nothing
 *   ATLAS_TRANSIT_W_CIRCLE    1 or 0 as for a stop; latitude, longitude, radius in metres
 *   ATLAS_TRANSIT_W_BOX       1 or 0; south, west, north, east
 *   ATLAS_TRANSIT_W_OUTLINE   1 or 0; then latitude and longitude of each corner
 *   ATLAS_TRANSIT_W_KIND      the bytes of a kind's name in UTF-8, a number each ("Intercity")
 * ATLAS_TRANSIT_MAX_WISHES at most. */
size_t atlas_transit_plan(atlas_transit *t, const int32_t *query, size_t query_len, const int32_t *from_walks,
                          size_t from_walks_len, const int32_t *to_walks, size_t to_walks_len, const int32_t *wishes,
                          size_t wishes_len);

/* Tells the plan under way on this timetable to stop: from any thread, at any time while the timetable is open.
 * The plan ends soon (it looks every 1,024 steps of its search: within some milliseconds on a country's file,
 * wherever it is), and its answer is what it had by then, with ATLAS_TRANSIT_CUT_STOPPED at
 * ATLAS_TRANSIT_A_CUT_SHORT: for whoever no longer waits for the answer (a new destination was typed, the screen
 * was left, the pack is being swapped). The word is for the plan that runs when it is said. With no plan under way
 * it does nothing, and the plan that begins afterwards runs whole: to stop a plan that has not begun, do not begin
 * it. */
void atlas_transit_stop(const atlas_transit *t);

/* Copies the last answer's numbers to `out`, `cap` of them at most, and gives how many it has. 0, with nothing
 * copied, while a plan on this timetable is under way.
 *
 * An answer: ATLAS_TRANSIT_A_HEAD numbers, then each journey, by departure. Of the head:
 *   ATLAS_TRANSIT_A_JOURNEYS    how many journeys follow
 *   ATLAS_TRANSIT_A_CUT_SHORT   0: the answer is whole. Else a cap was met, so an option may be missing (or one
 *                               leaves sooner than it need), and a bit says which, for whoever sets the caps:
 *                               ATLAS_TRANSIT_CUT_STEPS, _CUT_LABELS, _CUT_MOMENTS (more moments to search from
 *                               than _Q_DEPARTURES), _CUT_BAG, _CUT_OPTIONS, _CUT_PLACE (a place stood for more
 *                               stops than a search begins at), _CUT_DAYS (more service days could matter than a
 *                               search reads), _CUT_CHEAPER (with _CUT_STEPS: they ran out while a boarding read
 *                               on for a cheaper trip). An app says "cut short" for any of them. And
 *                               _CUT_STOPPED, which is no cap: atlas_transit_stop ended the plan, and nobody is
 *                               shown its answer.
 *   ATLAS_TRANSIT_A_HORIZON     how long past the asked moment journeys were followed, in seconds
 *   ATLAS_TRANSIT_A_SEARCHES, _A_LABELS, _A_STEPS_K, _A_LATER   what the search did, for a timing overlay
 *   ATLAS_TRANSIT_A_SPARE       0; nothing yet
 * A journey: ATLAS_TRANSIT_J_HEAD numbers, then its legs:
 *   ATLAS_TRANSIT_J_DEPART, _J_ARRIVE   seconds from the start of the asked service day
 *   ATLAS_TRANSIT_J_RIDES
 *   ATLAS_TRANSIT_J_PENALTY     what it costs in wishes, in seconds; 0 when it follows them all
 *   ATLAS_TRANSIT_J_WALKING     seconds on foot
 *   ATLAS_TRANSIT_J_LEGS        how many legs follow
 * A leg: ATLAS_TRANSIT_L_LEN numbers:
 *   ATLAS_TRANSIT_L_KIND        ATLAS_TRANSIT_L_WALK or ATLAS_TRANSIT_L_RIDE
 *   ATLAS_TRANSIT_L_FROM, _L_TO a stop; a walk's may be ATLAS_TRANSIT_ORIGIN or ATLAS_TRANSIT_DESTINATION
 *   ATLAS_TRANSIT_L_DEPART, _L_ARRIVE
 * and for a ride (a walk has ATLAS_TRANSIT_NONE, and 0 for the day):
 *   ATLAS_TRANSIT_L_TRIP, _L_ROUTE
 *   ATLAS_TRANSIT_L_DAY         the trip's own service day (the day before the asked one for a trip that left
 *                               before midnight)
 *   ATLAS_TRANSIT_L_BOARD, _L_ALIGHT   the places along the route where the rider gets on and off */
size_t atlas_transit_answer(const atlas_transit *t, int32_t *out, size_t cap);

/* Gives a timetable's working memory back to the C library: the search's lists and the lists of the days asked
 * about, which the next plan makes again (it takes that much longer, once). The index made at open stays, and so
 * does the last answer. For when nobody waits for a plan: the screen was left, the system asks for memory. 1: given
 * back. 0, and nothing done: no timetable; or a plan on it is under way or its answer is being read (ask again once
 * that has returned, or stop the plan first). */
int32_t atlas_transit_trim(atlas_transit *t);

#define ATLAS_TRANSIT_Q_FROM 0
#define ATLAS_TRANSIT_Q_TO 3
#define ATLAS_TRANSIT_Q_DAY 6
#define ATLAS_TRANSIT_Q_TIME 7
#define ATLAS_TRANSIT_Q_NEEDED 8 /* a query has at least so many numbers */
#define ATLAS_TRANSIT_Q_WINDOW 8
#define ATLAS_TRANSIT_Q_ARRIVE_BY 9
#define ATLAS_TRANSIT_Q_MAX_RIDES 10
#define ATLAS_TRANSIT_Q_REACH_M 11
#define ATLAS_TRANSIT_Q_WALK_MM_S 12
#define ATLAS_TRANSIT_Q_CHANGE_S 13
#define ATLAS_TRANSIT_Q_RULES 14
#define ATLAS_TRANSIT_Q_WEIGH 15
#define ATLAS_TRANSIT_Q_OPTIONS 16
#define ATLAS_TRANSIT_Q_DEPARTURES 17
#define ATLAS_TRANSIT_Q_HORIZON 18
#define ATLAS_TRANSIT_Q_WHOLE 19
#define ATLAS_TRANSIT_Q_BAG 20
#define ATLAS_TRANSIT_Q_LABELS 21
#define ATLAS_TRANSIT_Q_STEPS_K 22
#define ATLAS_TRANSIT_Q_LEN 23 /* and so many at most */

#define ATLAS_TRANSIT_P_STOP 0
#define ATLAS_TRANSIT_P_STATION 1
#define ATLAS_TRANSIT_P_POINT 2
#define ATLAS_TRANSIT_P_WALKS 3

#define ATLAS_TRANSIT_W_HEAD 4
#define ATLAS_TRANSIT_W_STOP 0
#define ATLAS_TRANSIT_W_STATION 1
#define ATLAS_TRANSIT_W_LINE 2
#define ATLAS_TRANSIT_W_OPERATOR 3
#define ATLAS_TRANSIT_W_MODE 4
#define ATLAS_TRANSIT_W_TRIP 5
#define ATLAS_TRANSIT_W_NO_WHEELCHAIR 6
#define ATLAS_TRANSIT_W_WHEELCHAIR_UNKNOWN 7
#define ATLAS_TRANSIT_W_NO_BICYCLES 8
#define ATLAS_TRANSIT_W_BICYCLES_UNKNOWN 9
#define ATLAS_TRANSIT_W_CIRCLE 10
#define ATLAS_TRANSIT_W_BOX 11
#define ATLAS_TRANSIT_W_OUTLINE 12
#define ATLAS_TRANSIT_W_KIND 13
#define ATLAS_TRANSIT_H_NEVER 0
#define ATLAS_TRANSIT_H_AVOID 1
#define ATLAS_TRANSIT_H_PREFER 2
#define ATLAS_TRANSIT_MAX_WISHES 64

#define ATLAS_TRANSIT_A_HEAD 8
#define ATLAS_TRANSIT_A_JOURNEYS 0
#define ATLAS_TRANSIT_A_CUT_SHORT 1
#define ATLAS_TRANSIT_A_HORIZON 2
#define ATLAS_TRANSIT_A_SEARCHES 3
#define ATLAS_TRANSIT_A_LABELS 4
#define ATLAS_TRANSIT_A_STEPS_K 5
#define ATLAS_TRANSIT_A_LATER 6
#define ATLAS_TRANSIT_A_SPARE 7
#define ATLAS_TRANSIT_CUT_STEPS 1
#define ATLAS_TRANSIT_CUT_LABELS 2
#define ATLAS_TRANSIT_CUT_MOMENTS 4
#define ATLAS_TRANSIT_CUT_BAG 8
#define ATLAS_TRANSIT_CUT_OPTIONS 16
#define ATLAS_TRANSIT_CUT_PLACE 32
#define ATLAS_TRANSIT_CUT_DAYS 64
#define ATLAS_TRANSIT_CUT_CHEAPER 128
#define ATLAS_TRANSIT_CUT_STOPPED 256
#define ATLAS_TRANSIT_J_HEAD 6
#define ATLAS_TRANSIT_J_DEPART 0
#define ATLAS_TRANSIT_J_ARRIVE 1
#define ATLAS_TRANSIT_J_RIDES 2
#define ATLAS_TRANSIT_J_PENALTY 3
#define ATLAS_TRANSIT_J_WALKING 4
#define ATLAS_TRANSIT_J_LEGS 5
#define ATLAS_TRANSIT_L_LEN 10
#define ATLAS_TRANSIT_L_KIND 0
#define ATLAS_TRANSIT_L_FROM 1
#define ATLAS_TRANSIT_L_TO 2
#define ATLAS_TRANSIT_L_DEPART 3
#define ATLAS_TRANSIT_L_ARRIVE 4
#define ATLAS_TRANSIT_L_TRIP 5
#define ATLAS_TRANSIT_L_ROUTE 6
#define ATLAS_TRANSIT_L_DAY 7
#define ATLAS_TRANSIT_L_BOARD 8
#define ATLAS_TRANSIT_L_ALIGHT 9
#define ATLAS_TRANSIT_L_WALK 0
#define ATLAS_TRANSIT_L_RIDE 1
#define ATLAS_TRANSIT_ORIGIN (-1)
#define ATLAS_TRANSIT_DESTINATION (-2)

#ifdef __cplusplus
}
#endif

#endif
