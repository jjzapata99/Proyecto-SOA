#include "redismodule.h"
#include <stdlib.h>
#include <string.h>

#define FILTER_SIZE 1000000

static int *filter_array = NULL;

unsigned long djb2_hash(const char *str, size_t len) {
    unsigned long hash = 5381;
    for (size_t i = 0; i < len; i++) {
        hash = ((hash << 5) + hash) + (unsigned char)str[i];
    }
    return hash % FILTER_SIZE;
}

int MFAdd_RedisCommand(RedisModuleCtx *ctx, RedisModuleString **argv, int argc) {
    if (argc != 3) return RedisModule_WrongArity(ctx);
    
    size_t len;
    const char *str = RedisModule_StringPtrLen(argv[2], &len);
    unsigned long idx = djb2_hash(str, len);
    
    int added = 0;
    if (filter_array[idx] == 0) {
        filter_array[idx] = 1;
        added = 1;
    }
    
    return RedisModule_ReplyWithLongLong(ctx, added);
}

int MFExists_RedisCommand(RedisModuleCtx *ctx, RedisModuleString **argv, int argc) {
    if (argc != 3) return RedisModule_WrongArity(ctx);
    
    size_t len;
    const char *str = RedisModule_StringPtrLen(argv[2], &len);
    unsigned long idx = djb2_hash(str, len);
    
    return RedisModule_ReplyWithLongLong(ctx, filter_array[idx]);
}

int RedisModule_OnLoad(RedisModuleCtx *ctx, RedisModuleString **argv, int argc) {
    if (RedisModule_Init(ctx, "mi_filtro", 1, REDISMODULE_APIVER_1) == REDISMODULE_ERR) {
        return REDISMODULE_ERR;
    }
    
    filter_array = (int *)calloc(FILTER_SIZE, sizeof(int));
    if (filter_array == NULL) return REDISMODULE_ERR;

    if (RedisModule_CreateCommand(ctx, "MF.ADD", MFAdd_RedisCommand, "write fast", 1, 1, 1) == REDISMODULE_ERR) {
        return REDISMODULE_ERR;
    }
    if (RedisModule_CreateCommand(ctx, "MF.EXISTS", MFExists_RedisCommand, "readonly fast", 1, 1, 1) == REDISMODULE_ERR) {
        return REDISMODULE_ERR;
    }

    return REDISMODULE_OK;
}
