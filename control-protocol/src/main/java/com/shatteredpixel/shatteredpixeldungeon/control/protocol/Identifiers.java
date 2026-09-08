package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

/** Identifier text must survive UTF-8 and SQLite text round trips without replacement. */
public final class Identifiers {
    private Identifiers(){}
    public static boolean valid(String text,int maximum){
        if(text==null||text.isEmpty()||text.length()>maximum)return false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isISOControl(c))return false;
            if(Character.isHighSurrogate(c)){
                if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))return false;
            }else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
}
