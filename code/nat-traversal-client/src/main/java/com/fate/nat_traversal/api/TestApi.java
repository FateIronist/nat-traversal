package com.fate.nat_traversal.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class TestApi {

    @GetMapping("/")
    @ResponseBody
    public String test() {
        return "Hello World!";
    }

    @GetMapping("/en1")
    @ResponseBody
    public String test1() {
        return "你好!1";
    }
    @GetMapping("/en2")
    @ResponseBody
    public String test2() {
        return "你好!2";
    }
    @GetMapping("/en3")
    @ResponseBody
    public String test3() {
        return "你好!3";
    }
    @GetMapping("/en4")
    @ResponseBody
    public String test4() {
        return "你好!4";
    }
    @GetMapping("/en5")
    @ResponseBody
    public String test5() {
        return "你好!5";
    }
    @GetMapping("/en6")
    @ResponseBody
    public String test6() {
        return "你好!6";
    }
    @GetMapping("/en7")
    @ResponseBody
    public String test7() {
        return "你好!7";
    }
    @GetMapping("/en8")
    @ResponseBody
    public String test8() {
        return "你好!8";
    }
    @GetMapping("/en9")
    @ResponseBody
    public String test9() {
        return "你好!9";
    }
    @GetMapping("/en10")
    @ResponseBody
    public String test10() {
        return "你好!10";
    }



}
