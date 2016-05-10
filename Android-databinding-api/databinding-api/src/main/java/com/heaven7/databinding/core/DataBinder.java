package com.heaven7.databinding.core;

import android.content.Context;

import com.heaven7.adapter.AdapterManager;
import com.heaven7.adapter.ISelectable;
import com.heaven7.core.util.ViewHelper;
import com.heaven7.databinding.core.xml.XmlElementNames;
import com.heaven7.databinding.core.xml.elements.DataBindingElement;
import com.heaven7.xml.XmlReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * the data bind controller
 * Created by heaven7 on 2015/8/10.
 */
public final class DataBinder implements IDataBinder{

    private final DataBindParser mDataBindParser;
    private int mBindRawResId;
    private XmlReader.Element mCacheXml;

    //==================================== temp  ========================================//

    /**
     * @param vp   the view helper
     * @param bindsRawResId  the raw resource id of data bind.
     * @param cacheXml to cache xml for reuse
     */
    DataBinder(ViewHelper vp ,int bindsRawResId,boolean cacheXml){
        this.mBindRawResId = bindsRawResId;
        this.mDataBindParser = new DataBindParser(vp, new BaseDataResolver());
        parseXml(vp.getContext(), bindsRawResId, cacheXml);
    }

    private void parseXml(Context context, int bindsRawResId,boolean cacheXml) {
        DataBindingElement dbe = new DataBindingElement(XmlElementNames.DATA_BINDING);
        dbe.addElementParseListener(mDataBindParser.getElementParserListener());
        if(mCacheXml!=null){
            dbe.parse(mCacheXml);
            dbe.clearElementParseListeners();
            return;
        }
        // parse bind xml
        InputStream in = context.getResources().openRawResource(bindsRawResId);
        try {
            if (!cacheXml){
                 dbe.parse(new XmlReader().parse(in));
            }else{
                mCacheXml = new XmlReader().parse(in);
                dbe.parse(mCacheXml);
            }
            dbe.clearElementParseListeners();
        } catch (IOException e) {
           throw new DataBindException(e);
        }finally{
            try {
                in.close();
            } catch (IOException e) {
                //ignore
            }
        }
    }

    @Override
    public void onDestroy(){
        mCacheXml = null;
        mDataBindParser.reset();
    }

    @Override
    public IDataBinder reset(){
        mDataBindParser.reset();
        parseXml(mDataBindParser.getContext(),mBindRawResId,false);
        mCacheXml = null;
        return this;
    }

    @Override
    public IDataBinder bind(int id, String propertyName, boolean cacheData, Object... datas){
        mDataBindParser.applyData(id, 0, propertyName, true, cacheData, datas);
        return this;
    }

    @Override
    public IDataBinder bind(int id , boolean cacheData, Object... datas){
        mDataBindParser.applyData(id, 0, true, cacheData, datas);
        return this;
    }

    @Override
    public IDataBinder bind(Object data, int... ids){
        mDataBindParser.applyData(data, ids);
        return this;
    }
    @Override
    public IDataBinder bind(String variable, Object data, int... ids){
        mDataBindParser.applyData(variable,data, ids);
        return this;
    }

    @Override
    public void notifyDataSetChanged(int viewId){
        mDataBindParser.notifyDataSetChanged(viewId);
    }

    @Override
    public void notifyDataSetChanged(int... viewIds) {
        for(int id : viewIds){
            notifyDataSetChanged(id);
        }
    }

    @Override
    @Deprecated
    public void notifyDataSetChanged(int viewId, String propertyName){
        mDataBindParser.notifyDataSetChangedByTargetProperty(viewId, propertyName);
    }

    @Override
    public void notifyDataSetChanged(String propertyName, int viewId) {
        mDataBindParser.notifyDataSetChangedByTargetProperty(viewId,propertyName);
    }

    @Override
    public void notifyDataSetChanged(String propertyName, int... ids) {
        for(int id : ids){
            notifyDataSetChanged( propertyName,id);
        }
    }

    @Override
    public <T extends ISelectable> AdapterManager<T> bindAdapter(int id, List<T> data,Object...extras) {
        return  mDataBindParser.bindAdapter(id,data,extras);
    }

    @Override
    public ViewHelper getViewHelper() {
        return mDataBindParser.getViewHelper();
    }

}
