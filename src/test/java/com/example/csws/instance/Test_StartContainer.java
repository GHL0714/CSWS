package com.example.csws.instance;

import com.example.csws.common.shRunner.ShRunner;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Transactional
public class Test_StartContainer {

    @Test
    void 컨테이너_시작() {

        ShRunner shRunner = new ShRunner();
        Map result = shRunner.execCommand("StartContainer.sh pika 192.168.50.49 ghlTest123");
        System.out.println(result.get(0) + "\n" + result.get(1));

    }

}
