package example;

import jasx.html.Html;
import java.util.List;

/** Prints the page, then the stack frame of an exception thrown in a .jasx file. */
public class Main {
    public static void main(String[] args) {
        System.out.println(Html.render(Pages.Home(List.of("Ada", "Grace"))));
        try {
            Pages.Broken();
        } catch (NumberFormatException e) {
            for (StackTraceElement frame : e.getStackTrace()) {
                if (frame.getClassName().equals("example.Pages")) {
                    System.out.println("thrown at " + frame); // example.Pages.Broken(Pages.jasx:25)
                }
            }
        }
    }
}
