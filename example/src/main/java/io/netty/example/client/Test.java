package io.netty.example.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Description:
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-21
 */
public class Test {
    private static final Logger LOG = LoggerFactory.getLogger(Test.class);

    public static void main(String[] args) {
        int pageSize = 8 * 1024;
        int subpageOverflowMask = ~(pageSize - 1);
        //  1111111111111 , 取反后就是13个0
        LOG.info("subpageOverflowMask -> {} ", subpageOverflowMask);

        int tinyPageSize = 256;
        LOG.info("tinyPageSize is subpage -> {}", (tinyPageSize & subpageOverflowMask) == 0);

        int smallPageSize = 6 * 1024;
        LOG.info("smallPageSize is subpage -> {}", (smallPageSize & subpageOverflowMask) == 0);

    }
}
