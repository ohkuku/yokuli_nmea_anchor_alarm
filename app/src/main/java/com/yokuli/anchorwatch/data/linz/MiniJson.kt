package com.yokuli.anchorwatch.data.linz

/** Small dependency-free JSON reader used for bounded LINZ GeoJSON responses. */
internal class MiniJson(private val text:String){
    private var index=0
    fun read():Any?{val value=value();space();require(index==text.length){"Trailing JSON"};return value}
    private fun value():Any?{space();require(index<text.length);return when(text[index]){'{'->obj();'['->array();'"'->string();'t'->{literal("true");true};'f'->{literal("false");false};'n'->{literal("null");null};else->number()}}
    private fun obj():Map<String,Any?>{index++;space();val result=linkedMapOf<String,Any?>();if(take('}'))return result;while(true){space();val key=string();space();require(take(':'));result[key]=value();space();if(take('}'))return result;require(take(','))}}
    private fun array():List<Any?>{index++;space();val result=mutableListOf<Any?>();if(take(']'))return result;while(true){result+=value();space();if(take(']'))return result;require(take(','))}}
    private fun string():String{require(text[index++]=='"');val out=StringBuilder();while(index<text.length){val c=text[index++];if(c=='"')return out.toString();if(c!='\\'){out.append(c);continue};val escaped=text[index++];out.append(when(escaped){'"'->'"';'\\'->'\\';'/'->'/';'b'->'\b';'f'->'\u000c';'n'->'\n';'r'->'\r';'t'->'\t';'u'->{val code=text.substring(index,index+4).toInt(16);index+=4;code.toChar()};else->error("Invalid escape")})};error("Unterminated string")}
    private fun number():Double{val start=index;while(index<text.length&&text[index] in "-+0123456789.eE")index++;return text.substring(start,index).toDouble()}
    private fun literal(expected:String){require(text.regionMatches(index,expected,0,expected.length));index+=expected.length}
    private fun take(char:Char):Boolean{if(index<text.length&&text[index]==char){index++;return true};return false}
    private fun space(){while(index<text.length&&text[index].isWhitespace())index++}
}
