/* The interface from C itself: this file sees nothing but ../include/atlas_transit.h and links the static library
 * as an app's shared library will. ../check.sh builds it, runs it on the hand-written feed (../../planner/tests/data/
 * edges.tt; ../../planner/tests/edges.rs has its islands) and holds what it prints to host.expected. */
#include "atlas_transit.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const atlas_transit *shown;

/* A name as a C string: the file's bytes are not ended by a zero. */
static const char *name(uint32_t what, uint32_t id, uint32_t at) {
    static char text[8][128];
    static int turn;
    size_t len = 0;
    const uint8_t *bytes = atlas_transit_text(shown, what, id, at, &len);
    char *out = text[turn++ % 8];
    if (len > 127) len = 127;
    memcpy(out, bytes, len);
    out[len] = 0;
    return out;
}

static int32_t stop_by_id(const char *id) {
    int32_t stops = atlas_transit_number(shown, ATLAS_TRANSIT_N_STOPS, 0, 0);
    for (int32_t s = 0; s < stops; s++) {
        if (strcmp(name(ATLAS_TRANSIT_T_STOP_ID, (uint32_t)s, 0), id) == 0) return s;
    }
    return ATLAS_TRANSIT_NONE;
}

static int32_t trip_by_id(const char *id) {
    int32_t trips = atlas_transit_number(shown, ATLAS_TRANSIT_N_TRIPS, 0, 0);
    for (int32_t t = 0; t < trips; t++) {
        if (strcmp(name(ATLAS_TRANSIT_T_TRIP_ID, (uint32_t)t, 0), id) == 0) return t;
    }
    return ATLAS_TRANSIT_NONE;
}

static const char *clock_of(int32_t seconds) {
    static char text[4][16];
    static int turn;
    char *out = text[turn++ % 4];
    snprintf(out, 16, "%02d:%02d", (int)(seconds / 3600), (int)(seconds / 60 % 60));
    return out;
}

static const char *spot(int32_t s) {
    if (s == ATLAS_TRANSIT_ORIGIN) return "the origin";
    if (s == ATLAS_TRANSIT_DESTINATION) return "the destination";
    return name(ATLAS_TRANSIT_T_STOP_NAME, (uint32_t)s, 0);
}

/* Plans, reads the answer into a buffer of its own length, and prints it. */
static void plan(atlas_transit *t, const char *what, const int32_t *query, size_t query_len, const int32_t *wishes, size_t wishes_len) {
    /* (a word to stop before a plan begins is for no plan: this one runs whole) */
    atlas_transit_stop(t);
    /* (and every plan here begins with the working memory given back: it makes its lists anew, and answers the same) */
    if (atlas_transit_trim(t) != 1 || atlas_transit_trim(NULL) != 0) {
        printf("%s: the working memory was not given back\n", what);
        exit(1);
    }
    size_t n = atlas_transit_plan(t, query, query_len, NULL, 0, NULL, 0, wishes, wishes_len);
    printf("%s: ", what);
    if (n == 0) {
        printf("refused\n");
        return;
    }
    int32_t *a = malloc(n * sizeof *a);
    if (a == NULL || atlas_transit_answer(t, a, n) != n) {
        printf("no answer\n");
        exit(1);
    }
    printf("%d journey(s)%s\n", (int)a[ATLAS_TRANSIT_A_JOURNEYS], a[ATLAS_TRANSIT_A_CUT_SHORT] ? ", cut short" : "");
    const int32_t *j = a + ATLAS_TRANSIT_A_HEAD;
    for (int32_t i = 0; i < a[ATLAS_TRANSIT_A_JOURNEYS]; i++) {
        printf("  %s to %s, %d ride(s), %d s on foot, cost %d\n", clock_of(j[ATLAS_TRANSIT_J_DEPART]), clock_of(j[ATLAS_TRANSIT_J_ARRIVE]),
               (int)j[ATLAS_TRANSIT_J_RIDES], (int)j[ATLAS_TRANSIT_J_WALKING], (int)j[ATLAS_TRANSIT_J_PENALTY]);
        const int32_t *l = j + ATLAS_TRANSIT_J_HEAD;
        for (int32_t k = 0; k < j[ATLAS_TRANSIT_J_LEGS]; k++, l += ATLAS_TRANSIT_L_LEN) {
            if (l[ATLAS_TRANSIT_L_KIND] == ATLAS_TRANSIT_L_RIDE) {
                uint32_t route = (uint32_t)l[ATLAS_TRANSIT_L_ROUTE];
                uint32_t line = (uint32_t)atlas_transit_number(t, ATLAS_TRANSIT_N_ROUTE_LINE, route, 0);
                printf("    %s %s %s to %s %s: trip %s of line %s to \"%s\"\n", clock_of(l[ATLAS_TRANSIT_L_DEPART]), spot(l[ATLAS_TRANSIT_L_FROM]),
                       "ride", clock_of(l[ATLAS_TRANSIT_L_ARRIVE]), spot(l[ATLAS_TRANSIT_L_TO]), name(ATLAS_TRANSIT_T_TRIP_ID, (uint32_t)l[ATLAS_TRANSIT_L_TRIP], 0),
                       name(ATLAS_TRANSIT_T_LINE_ID, line, 0), name(ATLAS_TRANSIT_T_ROUTE_HEADSIGN, route, (uint32_t)l[ATLAS_TRANSIT_L_BOARD]));
            } else {
                printf("    %s %s walk to %s %s\n", clock_of(l[ATLAS_TRANSIT_L_DEPART]), spot(l[ATLAS_TRANSIT_L_FROM]), clock_of(l[ATLAS_TRANSIT_L_ARRIVE]),
                       spot(l[ATLAS_TRANSIT_L_TO]));
            }
        }
        j = l;
    }
    if ((size_t)(j - a) != n) {
        printf("an answer of %zu numbers, read as %zu\n", n, (size_t)(j - a));
        exit(1);
    }
    free(a);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: host <timetable>\n");
        return 2;
    }
    FILE *f = fopen(argv[1], "rb");
    if (f == NULL || fseek(f, 0, SEEK_END) != 0) {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 2;
    }
    long size = ftell(f);
    rewind(f);
    uint8_t *bytes = malloc((size_t)size);
    if (bytes == NULL || fread(bytes, 1, (size_t)size, f) != (size_t)size) {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 2;
    }
    fclose(f);

    printf("the interface: %u, and the header's: %d\n", (unsigned)atlas_transit_abi(), ATLAS_TRANSIT_ABI);
    if (atlas_transit_abi() != ATLAS_TRANSIT_ABI) return 1;

    /* what is no timetable is not opened, and the reason is said */
    int32_t why = -7;
    atlas_transit *none = atlas_transit_open((const uint8_t *)"no timetable, whatever it says", 30, 0, &why);
    printf("thirty bytes of something else: %s, reason %d\n", none == NULL ? "not opened" : "opened", (int)why);
    none = atlas_transit_open(bytes, (size_t)size / 2, 0, &why);
    printf("half the file: %s, reason %d\n", none == NULL ? "not opened" : "opened", (int)why);

    /* nor a timetable whose index would take more memory than the caller allows: here a hundred bytes */
    none = atlas_transit_open(bytes, (size_t)size, 100, &why);
    printf("the file, its index held to a hundred bytes: %s, reason %d\n", none == NULL ? "not opened" : "opened", (int)why);
    if (none != NULL || why != ATLAS_TRANSIT_INDEX_TOO_LARGE) return 1;

    atlas_transit *t = atlas_transit_open(bytes, (size_t)size, 0, &why);
    printf("the file: %s, reason %d\n", t == NULL ? "not opened" : "opened", (int)why);
    if (t == NULL) return 1;
    shown = t;
    size_t len = 0;
    const uint8_t *zone = atlas_transit_meta(t, (const uint8_t *)"timezone", 8, &len);
    printf("from %d, %d days, %d stops, %d lines, %d trips; %.*s\n", (int)atlas_transit_number(t, ATLAS_TRANSIT_N_FIRST_DAY, 0, 0),
           (int)atlas_transit_number(t, ATLAS_TRANSIT_N_DAYS, 0, 0), (int)atlas_transit_number(t, ATLAS_TRANSIT_N_STOPS, 0, 0),
           (int)atlas_transit_number(t, ATLAS_TRANSIT_N_LINES, 0, 0), (int)atlas_transit_number(t, ATLAS_TRANSIT_N_TRIPS, 0, 0), (int)len, (const char *)zone);
    printf("27 March 2027 is day %d, 30 February is %s, day 3 is %d\n", (int)atlas_transit_number(t, ATLAS_TRANSIT_N_DAY_OF, 20270327, 0),
           atlas_transit_number(t, ATLAS_TRANSIT_N_DAY_OF, 20270230, 0) == ATLAS_TRANSIT_NO_DAY ? "no day" : "a day",
           (int)atlas_transit_number(t, ATLAS_TRANSIT_N_DATE_OF, 3, 0));

    /* Wacht: the bus from C waits for the bus that is in at B */
    int32_t query[ATLAS_TRANSIT_Q_LEN];
    for (size_t i = 0; i < ATLAS_TRANSIT_Q_LEN; i++) query[i] = -1; /* the planner's own, where nothing else is said */
    query[ATLAS_TRANSIT_Q_FROM] = ATLAS_TRANSIT_P_STOP;
    query[ATLAS_TRANSIT_Q_FROM + 1] = stop_by_id("WA");
    query[ATLAS_TRANSIT_Q_TO] = ATLAS_TRANSIT_P_STOP;
    query[ATLAS_TRANSIT_Q_TO + 1] = stop_by_id("WZ");
    query[ATLAS_TRANSIT_Q_DAY] = 0;
    query[ATLAS_TRANSIT_Q_TIME] = 7 * 3600 + 55 * 60;
    plan(t, "Wacht at 07:55", query, ATLAS_TRANSIT_Q_LEN, NULL, 0);
    plan(t, "Wacht at 07:55, by the eight numbers a query needs", query, ATLAS_TRANSIT_Q_NEEDED, NULL, 0);
    query[ATLAS_TRANSIT_Q_ARRIVE_BY] = 1;
    query[ATLAS_TRANSIT_Q_TIME] = 8 * 3600 + 29 * 60;
    plan(t, "Wacht arriving by 08:29", query, ATLAS_TRANSIT_Q_LEN, NULL, 0);

    /* Lijn: the bus that waits, avoided by forty minutes, and the next one that costs nothing */
    query[ATLAS_TRANSIT_Q_ARRIVE_BY] = 0;
    query[ATLAS_TRANSIT_Q_FROM + 1] = stop_by_id("NA");
    query[ATLAS_TRANSIT_Q_TO + 1] = stop_by_id("NZ");
    query[ATLAS_TRANSIT_Q_TIME] = 7 * 3600 + 55 * 60;
    int32_t wish[ATLAS_TRANSIT_W_HEAD + 1] = {ATLAS_TRANSIT_W_TRIP, ATLAS_TRANSIT_H_AVOID, 2400, 1, 0};
    wish[ATLAS_TRANSIT_W_HEAD] = trip_by_id("N2a");
    plan(t, "Lijn at 07:55, the 08:11 avoided by forty minutes", query, ATLAS_TRANSIT_Q_LEN, wish, ATLAS_TRANSIT_W_HEAD + 1);
    query[ATLAS_TRANSIT_Q_OPTIONS] = 1;
    plan(t, "the same, one option", query, ATLAS_TRANSIT_Q_LEN, wish, ATLAS_TRANSIT_W_HEAD + 1);
    query[ATLAS_TRANSIT_Q_OPTIONS] = -1;
    plan(t, "the same, the wish cut off", query, ATLAS_TRANSIT_Q_LEN, wish, ATLAS_TRANSIT_W_HEAD);
    wish[0] = 99;
    plan(t, "the same, a wish of no known kind", query, ATLAS_TRANSIT_Q_LEN, wish, ATLAS_TRANSIT_W_HEAD + 1);

    /* Lopen: between two points, the walk all the way and the bus */
    query[ATLAS_TRANSIT_Q_FROM] = ATLAS_TRANSIT_P_POINT;
    query[ATLAS_TRANSIT_Q_FROM + 1] = atlas_transit_number(t, ATLAS_TRANSIT_N_STOP_LAT, (uint32_t)stop_by_id("PA"), 0);
    query[ATLAS_TRANSIT_Q_FROM + 2] = atlas_transit_number(t, ATLAS_TRANSIT_N_STOP_LON, (uint32_t)stop_by_id("PA"), 0);
    query[ATLAS_TRANSIT_Q_TO] = ATLAS_TRANSIT_P_POINT;
    query[ATLAS_TRANSIT_Q_TO + 1] = atlas_transit_number(t, ATLAS_TRANSIT_N_STOP_LAT, (uint32_t)stop_by_id("PZ"), 0);
    query[ATLAS_TRANSIT_Q_TO + 2] = atlas_transit_number(t, ATLAS_TRANSIT_N_STOP_LON, (uint32_t)stop_by_id("PZ"), 0);
    query[ATLAS_TRANSIT_Q_TIME] = 8 * 3600;
    plan(t, "Lopen at 08:00, between two points", query, ATLAS_TRANSIT_Q_LEN, NULL, 0);

    atlas_transit_close(t);
    atlas_transit_close(NULL);
    free(bytes);
    printf("closed\n");
    return 0;
}
