package org.javacs.example;

import lombok.Data;

@Data
class Boz {
    private Reference pojo;
}

class Reference {}

@Data
class Features {
    private boolean enabled;
}

@Data
class ApplicationException extends Exception {
    private String error;
}

public class LombokSetterBooleanScopeExample {
    void setterAndGetter(Boz exchange) {
        exchange.setPojo(new Reference());
        exchange.getPojo();
    }

    boolean checkFeatures(Features features) {
        if (features.isEnabled()) {
            return true;
        }
        return false;
    }

    String checkScopes(Boz foo, ApplicationException y) {
        if (foo.getPojo() != null) {
            return "ok";
        }
        try {
            throw y;
        } catch (ApplicationException ex) {
            if ("X".equals(ex.getError())) {
                return ex.getError();
            }
        }
        return "missing";
    }
}
