#ifndef LIBWORDEXP_WORDEXP_H
#define LIBWORDEXP_WORDEXP_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

typedef struct {
    size_t we_wordc;
    char **we_wordv;
    size_t we_offs;
} wordexp_t;

enum {
    WRDE_APPEND   = 0x01,
    WRDE_DOOFFS   = 0x02,
    WRDE_NOCMD    = 0x04,
    WRDE_REUSE    = 0x08,
    WRDE_SHOWERR  = 0x10,
    WRDE_UNDEF    = 0x20
};

enum {
    WRDE_BADCHAR = 1,
    WRDE_BADVAL  = 2,
    WRDE_CMDSUB  = 3,
    WRDE_NOSPACE = 4,
    WRDE_SYNTAX  = 5,
    WRDE_NOSYS   = 6
};

static inline int wordexp(const char *words, wordexp_t *pwordexp, int flags) {
    (void)words;
    (void)flags;
    if (pwordexp) {
        pwordexp->we_wordc = 0;
        pwordexp->we_wordv = (char **)0;
        pwordexp->we_offs = 0;
    }
    return WRDE_NOSYS;
}

static inline void wordfree(wordexp_t *pwordexp) {
    if (!pwordexp) {
        return;
    }
    pwordexp->we_wordc = 0;
    pwordexp->we_wordv = (char **)0;
    pwordexp->we_offs = 0;
}

#ifdef __cplusplus
}
#endif

#endif
