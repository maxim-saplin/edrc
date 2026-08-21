package com.saplin.edrc;

interface IDumpService {
    void destroy() = 16777114;
    String collectFrame(boolean force) = 1;
    String getStateJson() = 2;
}
