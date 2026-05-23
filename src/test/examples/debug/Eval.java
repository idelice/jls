class Eval {

    static int classVar = 1;
    int objVar;
    
    public static void main(String[] args) {
        Eval eval = new Eval();
        String message = eval.getMessage("world");
        System.out.println(message);
    }

    public String getMessage(String text) {
        objVar = 2;
        String localVar = "Hello ";
        String result = localVar + text;
        return result;
    }

}
