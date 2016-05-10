package com.heaven7.databinding.core;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;

import com.heaven7.adapter.AdapterManager;
import com.heaven7.adapter.ISelectable;
import com.heaven7.core.util.Logger;
import com.heaven7.core.util.ViewHelper;
import com.heaven7.databinding.api.R;
import com.heaven7.databinding.core.expression.IExpression;
import com.heaven7.databinding.core.listener.ListenerImplContext;
import com.heaven7.databinding.core.xml.elements.BindAdapterElement;
import com.heaven7.databinding.core.xml.elements.BindElement;
import com.heaven7.databinding.core.xml.elements.DataBindingElement;
import com.heaven7.databinding.core.xml.elements.DataElement;
import com.heaven7.databinding.core.xml.elements.ImportElement;
import com.heaven7.databinding.core.xml.elements.PropertyElement;
import com.heaven7.databinding.core.xml.elements.VariableElement;
import com.heaven7.databinding.util.ArrayUtil;
import com.heaven7.databinding.util.IResetable;
import com.heaven7.databinding.util.Objects;
import com.heaven7.databinding.util.ResourceUtil;
import com.heaven7.xml.Array;
import com.heaven7.xml.ObjectMap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.heaven7.databinding.core.BindUtil.checkDatasExist;
import static com.heaven7.databinding.core.BindUtil.containsAll;
import static com.heaven7.databinding.core.BindUtil.convert2BindInfos;
import static com.heaven7.databinding.core.BindUtil.releasePropertyBindInfos;
import static com.heaven7.databinding.core.ListenerFactory.createEventListener;
import static com.heaven7.databinding.core.ListenerFactory.isEventProperty;
import static com.heaven7.databinding.core.PropertyUtil.apply;
import static com.heaven7.databinding.core.PropertyUtil.checkAndGetImageApplier;
import static com.heaven7.databinding.core.PropertyUtil.getEventKey;

/**
 * data bind really implements.
 * Created by heaven7 on 2015/8/11.
 */
public class DataBindParser implements IDataResolver.IEventEvaluateCallback{

    private static final boolean sDebug = false ;

    private static final String TAG = "DataBinding";

    private final IDataResolver mDataResolver;
    private final InternalElementParserListener mParserListenerImpl;
    private final EventParseCaretaker mEventCareTaker;
    private BindAdapterParser mAdapterParser;

    /** key is combination of view id and property name */
    private final SparseArray<ListenerImplContext> mListenerMap;

    private SparseArray<Array<PropertyBindInfo>> mBindMap_viewId;        //key is view id
    private SparseArray<Array<PropertyBindInfo>> mBindMap_variable;      //key is varaible name's hashCode
    private ViewHelper mViewHelper;

    /**
     *  <variable name="user"  classname="com.heaven7.databinding.demo.bean.User"  type="bean"/>
     *  key,value -> $classname,$user
     */
    private final ObjectMap<String,String> mVariableBeanMap ;
    private final ObjectMap<String,String> mVariableCallbakMap;


    /** the cached variable infos for later reuse , this is used for  notifyDataSetChanged() --> not adapter,
     * key --> (  id,<propertyName.hashCode().Array<VariableInfo>> ) ,*/
    private final SparseArray<SparseArray<Array<VariableInfo>>> mViewVariableInfos;

    //==================start for tmp use , just avoid reallocate memory ============//
    private final Array<VariableInfo> mTmpVariables ;
    private HashSet<String> mTmpVarStrs;
    //================== end for tmp use , just avoid reallocate memory ============//

    /*public*/ DataBindParser(ViewHelper vp,IDataResolver resolver){
        this.mViewHelper = vp ;
        mDataResolver = resolver ;
        resolver.setEventEvaluateCallback(this);

        mBindMap_viewId = new SparseArray<>();
        mVariableBeanMap = new ObjectMap<String,String>(8);
        mVariableCallbakMap = new ObjectMap<String,String>(8);
        mViewVariableInfos = new SparseArray<>(8);

        mParserListenerImpl = new InternalElementParserListener();
        mTmpVariables = new Array<>(6);

        mListenerMap = new SparseArray<>();
        mEventCareTaker = new EventParseCaretaker();
    }
    /*public*/ DataBindParser(@NonNull View root,IDataResolver resolver) {
        this(new ViewHelper(root),resolver);
    }

    /** reset the bind data cache */
    public void reset(View root){
        if(root == null) {
            root = mViewHelper.getRootView();
        }
        mViewHelper = new ViewHelper(root);

        mDataResolver.reset();
        mVariableBeanMap.clear();
        mVariableCallbakMap.clear();
        mListenerMap.clear();
        if(mAdapterParser !=null )
            mAdapterParser.reset();

        releasePropertyBindInfos(mBindMap_viewId);
        releasePropertyBindInfos(mBindMap_variable);

        SparseArray<SparseArray<Array<VariableInfo>>> mViewVariableInfos = this.mViewVariableInfos;
        for(int i =0,size = mViewVariableInfos.size() ;i < size ;i++){
            mViewVariableInfos.valueAt(i).clear();
        }
        mViewVariableInfos.clear();
    }

    public void reset(){
         reset(null);
    }

    public Context getContext(){
        return mViewHelper.getContext();
    }

    public DataBindingElement.IElementParseListener getElementParserListener(){
       return mParserListenerImpl;
    }
    private void doWithImportElement(ImportElement ie) {
        String alias = ie.getAlias();
        String classname = ie.getClassname();//full class name
        if(!classname.contains(".")) throw new RuntimeException("class name must be full name.");
        if(TextUtils.isEmpty(alias)){
            alias = classname.substring(classname.lastIndexOf(".")+1);
        }
        mDataResolver.putClassname(alias, classname);
    }

    private void doWithBindElement(BindElement be) {
        int id = ResourceUtil.getResId(getContext(), be.getId(), ResourceUtil.ResourceType.Id);

        List<PropertyElement> propEles = be.getPropertyElements();
        if(propEles!=null && propEles.size()>0){
            SparseArray<Array<PropertyBindInfo>> mBindMap = this.mBindMap_viewId;
            final Array<PropertyBindInfo> infos = new Array<>(8);
            mBindMap.put(id,infos);

            convert2BindInfos(getContext(), propEles, infos);
        }
    }

    private void doWithVariableElement(VariableElement ve) {
        if(sDebug){
            System.out.println("doWithVariableElement(): " + ve.toString());
        }
        final String type = ve.getType();
        if(VariableType.CALLBACK.equals(type)){
            //event
            mVariableCallbakMap.put(ve.getClassname(),ve.getName());
            mDataResolver.addEventHandlerVariable(ve.getName());
        }else {
            mVariableBeanMap.put(ve.getClassname(), ve.getName());
        }
    }

    /**
     * apply the datas to the target propertyName of id.
     * @param id  the view to populate
     * @param type   {@link DataBinder#TYPE_BEAN} and etc. not use now
     * @param propertyName  the property name to bind,must declared it in xml , or else an exception will throw
     * @param datas  the datas to populate
     * @param checkStrictly check the all variable defined in xml can find mapping, if not throw an Exception
     */
    public void applyData(int id,int type,String propertyName ,boolean checkStrictly,boolean cacheData,
                          Object...datas){
        checkDatasExist(datas);
        mDataResolver.setCurrentBindingView(mViewHelper.getView(id));
        final PropertyBindInfo bindInfo = getBindInfo(id, propertyName);
        final Array<VariableInfo> mTmpVariables = getAllVariables(datas);

        applyDataInternal(id, bindInfo, mTmpVariables, checkStrictly);
        if(cacheData)
             addToVariableInfoCache(id, mTmpVariables, bindInfo);
        mTmpVariables.clear();
        //clear data
        mDataResolver.clearObjects();
    }

    /**
     *  add to variable info cache for later reuse. eg: {@link #notifyDataSetChanged(int)} or
     *  {@link #notifyDataSetChangedByTargetProperty(int, String)}
     * @param id   view id
     * @param varInfos    VariableInfo to map the property
     * @param bindInfo         the target property
     */
    private void addToVariableInfoCache(int id, Array<VariableInfo> varInfos, PropertyBindInfo bindInfo) {
        final int secondKey = bindInfo.propertyName.hashCode();
        SparseArray<Array<VariableInfo>> arr = mViewVariableInfos.get(id);
        if(arr != null){
            //check if have the target property record
            Array<VariableInfo> infos = arr.get(secondKey);
            if(infos == null){
                infos = new Array<>(4);
                arr.put(secondKey,infos);
            }else {
                infos.clear();
            }
            infos.addAll(varInfos);
        }else{
            //put to 1 map
            arr = new SparseArray<>(4);
            mViewVariableInfos.put(id,arr);
            //put to 2 map
            Array<VariableInfo> infos = new Array<>(4);
            arr.put(secondKey,infos);
            infos.addAll(varInfos);
        }
    }

    /**
     * @param id   the view id
     * @param propertyName  the property name to bind,must declared it in xml , or else an exception will throw
     * @return PropertyBindInfo
     */
    private PropertyBindInfo getBindInfo(int id, String propertyName) {
        final Array<PropertyBindInfo> array = mBindMap_viewId.get(id);
        PropertyBindInfo info;
        for(int i =0 ,size = array.size ; i<size ; i++){
            info = array.get(i);
            if(info.propertyName.equals(propertyName)){
                return info;
            }
        }
        throw new DataBindException("can't find the bind info , have you declared it in xml ? ");
    }

    /**
     * notify the data set changed with the target view
     * @param id the view id
     */
    public void notifyDataSetChanged(int id){
        final Array<VariableInfo> variables = getAllVariableInfosById(id);
        if(variables == null){
            throw new DataBindException("the id = " + id +" haven't bind any data yet," +
                    " so can't call notifyDataSetChanged() mMethod !");
        }
        applyDataInternal(id, null, variables,false);
        mTmpVariables.clear();
        mDataResolver.clearObjects();
    }

    /**
     * apply data with the all property expression of target id .
     * @param id  view id
     * @param variables  the pairs of variable and bind data to apply
     * @param info  the property info to bind  or null to bind all property of target view
     * @param checkStrictly check the all variable defined in xml can find mapping, if not throw an Exception
     */
    private void applyDataInternal(int id, PropertyBindInfo info, Array<VariableInfo> variables,
                                   boolean checkStrictly) {
        //check , put data and apply
        if(info == null) {
            final ViewHelper mViewHelper = this.mViewHelper;
            final IDataResolver mDataResolver = this.mDataResolver;
            final SparseArray<ListenerImplContext> mListenerMap = this.mListenerMap;
            final EventParseCaretaker caretaker = this.mEventCareTaker;

            Array<PropertyBindInfo> array = mBindMap_viewId.get(id);
            for (int i = 0, size = array.size; i < size; i++) {
                applyDataInternal0(id, variables, array.get(i), mViewHelper, mDataResolver,
                        checkStrictly,mListenerMap,caretaker);
            }
        }else{
            applyDataInternal0(id, variables, info, mViewHelper, mDataResolver,
                    checkStrictly,mListenerMap,mEventCareTaker);
        }
    }

    /** notify data set changed by target property of target view(id indicate)  */
    public void notifyDataSetChangedByTargetProperty(int id ,String propertyName){
        final SparseArray<Array<VariableInfo>> varInfos = this.mViewVariableInfos.get(id);
        if(varInfos == null){
            throw new DataBindException("the id = " + id +" haven't bind any data yet," +
                    " so can't call notifyDataSetChangedByTargetProperty() mMethod !");
        }
        final Array<VariableInfo> infos = varInfos.get(propertyName.hashCode());
        if(infos == null){
            throw new DataBindException("the id ( id = "+id+" ) with target property ( propertyName = " + propertyName
                    +" ) haven't bind any data yet, so can't call notifyDataSetChangedByTargetProperty() mMethod !");
        }
        applyDataInternal(id, getBindInfo(id, propertyName), infos, false);
        mDataResolver.clearObjects();
    }

    private Array<VariableInfo> getAllVariableInfosById(int id) {
        final SparseArray<Array<VariableInfo>> varInfos = this.mViewVariableInfos.get(id);
        if(varInfos == null){
            return null;
        }
        final Array<VariableInfo> mTempVarInfos = mTmpVariables;
        final int size = varInfos.size();
        for(int i = size - 1 ; i >= 0 ; i-- ){
            mTempVarInfos.addAll(varInfos.valueAt(i));
        }
        return mTempVarInfos;
    }

    /***
     * bind the all data to all property of view
     * @param datas the data to bind
     * @param type  {@link DataBinder#TYPE_BEAN} and etc. now not used.
     * @param id   the view id
     * @param checkStrictly  check the all variable defined in xml can find mapping, if not throw Exception
     */
    public void applyData(int id, int type, boolean checkStrictly ,boolean cacheData,Object... datas) {
        checkDatasExist(datas);
        //check , put data and apply
        mDataResolver.setCurrentBindingView(mViewHelper.getView(id));
        if(cacheData){
            final ViewHelper mViewHelper = this.mViewHelper;
            final IDataResolver mDataResolver = this.mDataResolver;
            final EventParseCaretaker caretaker = this.mEventCareTaker;
            final SparseArray<ListenerImplContext> mListenerMap = this.mListenerMap;

            final Array<VariableInfo> propVarInfos = new Array<>(4);

            Array<PropertyBindInfo> array = mBindMap_viewId.get(id);
            if(checkStrictly) {
                checkReferVariables(id, array, datas);
            }

            PropertyBindInfo info ;
            for (int i = 0, size = array.size; i < size; i++) {
                info = array.get(i);
                getReferVariableInfos(info.referVariables, datas, propVarInfos);
                //here  checkStrictly must be false, to ignore unnecessary exception
                applyDataInternal0(id, propVarInfos, info, mViewHelper, mDataResolver,
                        false, mListenerMap,caretaker);
                addToVariableInfoCache(id,propVarInfos,info);
                propVarInfos.clear();
            }
        }else {
            if(checkStrictly) {
                checkReferVariables(id, mBindMap_viewId.get(id), datas);
            }
            Array<VariableInfo> mTmpVariables = getAllVariables(datas);
            //here checkStrictly must be false, to ignore unnecessary exception
            applyDataInternal(id, null, mTmpVariables, false);
        }

        this.mTmpVariables.clear();
        //clear datas refer
        mDataResolver.clearObjects();
    }

    private void checkReferVariables(int id,Array<PropertyBindInfo> array, Object... datas) {
        if(datas==null || datas.length==0 || array.size == 0)
             return;
        final Array<VariableInfo> mTmpVariables = getAllVariables(datas);
        Set<String> vars = mTmpVarStrs!=null ? mTmpVarStrs : (mTmpVarStrs = new HashSet<String>());
        PropertyBindInfo info;
        for(int i=0,size = array.size ;i < size ;i++){
            info = array.get(i);
            if(info.referVariables!=null && info.referVariables.length > 0) {
                vars.addAll(Arrays.asList(info.referVariables));
            }
        }
        if(!containsAll(mTmpVariables,vars)){
            String msg = "the property [ id = "+ id +"] defined in xml can't be apply ," +
                    "caused by some data mapping is missing !";
            throw new DataBindException(msg);
        }
        mTmpVariables.clear();
        vars.clear();
    }

    /** find the all refer variable info, the data's area may be larger.
     * @throws DataBindException if any data can't find mapping */
    private void getReferVariableInfos(String[] referVars, Object[] datas,
                                       Array<VariableInfo> outVarInfos) throws DataBindException {
        if(sDebug) {
            Logger.i(TAG, "getReferVariableInfos", "referVars = " + Arrays.toString(referVars)
                    + " ,datas = " + Arrays.toString(datas));
        }
       /* if(referVars != null && referVars.length > 0 && (datas == null || datas.length == 0)){
           throw new DataBindException("can't find the mapping data, referVars = "+ Arrays.toString(referVars)
                   +" ,datas = " + Arrays.toString(datas));
        }*/
        String varName;
        for(int i=0,size = datas.length ; i<size ;i++){
            varName = getVariableName(datas[i].getClass().getName());
            if(sDebug){
                Logger.i(TAG, "getReferVariableInfos", "varName : " + varName);
            }
            if(ArrayUtil.contains(referVars, varName)){
                outVarInfos.add(new VariableInfo(varName,datas[i]));
            }
        }
    }

    private Array<VariableInfo> getAllVariables(Object[] datas) throws DataBindException{
        final Array<VariableInfo> mTmpVariables = this.mTmpVariables;
        Object data;
        for(int i=0 ,size = datas.length ; i<size ;i++){
            data = datas[i];
            if(data == null){
                throw new DataBindException("array datas can't contains null element !");
            }
            mTmpVariables.add(new VariableInfo(getVariableName(
                    data.getClass().getName()), data));
        }
        return mTmpVariables;
    }

    @android.support.annotation.NonNull
    private String getVariableName(String className) throws DataBindException{
        if(sDebug){
            Logger.d(TAG, "getVariableName", "className = " + className);
        }
        String var = mVariableBeanMap.get(className);
        if(var == null){
            var = mVariableCallbakMap.get(className);
        }
        if(var == null){
            throw new DataBindException("There is no mapping of the data's class, the classname = " +className
            +" , have you declare the <variable> ?");
        }
        return var;
    }

    /**
     *  apply the data to the all view of binds
     * @param data  the data
     * @param ids the ids of view ,can be null
     */
    public void applyData(Object data, int... ids) {
        applyData(null, data, ids);
    }
    /**
     *  apply the data to the all view of binds
     * @param data  the data
     * @param ids the ids of view ,can be null
     */
    public void applyData(String variable,Object data, int... ids) {
        if(data == null){
            throw new NullPointerException();
        }
        if(variable == null) {
            variable = mVariableBeanMap.get(data.getClass().getName());
            if (variable == null) {
                throw new DataBindException("can't find the mapping, have you declare the variable in  '<data>'?");
            }
        }
        final Array<PropertyBindInfo> infos = mBindMap_variable.get(variable.hashCode());
        if(infos == null || infos.size==0){
            throw new DataBindException("can't find any mapping of variable ( "+ variable+" ) in <bind> element");

        }
        final ViewHelper mViewHelper = this.mViewHelper;
        final IDataResolver mDataResolver = this.mDataResolver;
        final SparseArray<ListenerImplContext> mListenerMap = this.mListenerMap;
        final EventParseCaretaker caretaker = this.mEventCareTaker;
        mDataResolver.putObject(variable,data);

        PropertyBindInfo info;
        final boolean checkId = ids!=null && ids.length > 0 ;
        for(int i=0,size = infos.size ; i<size ;i++){
            info = infos.get(i);
            if(!checkId || ArrayUtil.contains(ids,info.viewId)){
                mDataResolver.setCurrentBindingView(mViewHelper.getView(info.viewId));
                applyDataReally(info.viewId, 0 , info, mViewHelper, mDataResolver,mListenerMap,caretaker);
            }
        }
        mDataResolver.clearObjects();
    }

    private void doWithVariableBindElement(BindElement be) {
        if(mBindMap_variable == null)
            mBindMap_variable = new SparseArray<>(3);

        List<PropertyElement> propEles = be.getPropertyElements();
        if(propEles!=null && propEles.size()>0){
            SparseArray<Array<PropertyBindInfo>> mBindMap = this.mBindMap_variable;
            final Array<PropertyBindInfo> infos = new Array<>(8);
            mBindMap.put(be.getReferVariable().hashCode(), infos);

            convert2BindInfos(getContext(),propEles,infos);
        }
    }

    /** apply data of one target PropertyBindInfo , this will auto add obj(the bind data) to IDataResolver,
     * so you might call clear after call this. */
    private static void applyDataInternal0(int id, Array<VariableInfo> mTmpVariables,
                                           PropertyBindInfo info, ViewHelper mViewHelper,
                                           IDataResolver mDataResolver, boolean checkStrictly,
                                           SparseArray<ListenerImplContext> mListenerMap ,
                                           EventParseCaretaker caretaker) {
        if(!checkStrictly || containsAll(mTmpVariables,info.referVariables)){
            if(!checkStrictly) {
                if(sDebug) {
                    String msg = "the property [ id = " + id + " ,name = " + info.propertyName
                            + "] defined in xml may couldn't be apply , you should be careful!";
                    Logger.d(TAG, msg);
                }
            }
            VariableInfo varInfo;
            for(int j = 0, len = mTmpVariables.size ; j< len ; j++){
                varInfo = mTmpVariables.get(j);
                mDataResolver.putObject(varInfo.variableName, varInfo.data);
            }
            applyDataReally(id, 0, info, mViewHelper, mDataResolver, mListenerMap, caretaker);
        } else{
            String msg = "the property [ id = "+ id +" ,name = "+info.propertyName
                    +"] defined in xml can't be apply ,caused by some data mapping is missing !";
            throw new DataBindException(msg);
        }
    }

    /**
     * @param id  the view id .
     * @param layoutId  the layout id . this is only used in adapter view . often is 0 if not in adapter.
     * @param info  may be {@link com.heaven7.databinding.core.DataBindParser.ImagePropertyBindInfo}
     */
    private static void applyDataReally(int id, int layoutId,PropertyBindInfo info, ViewHelper vp,
                                        IDataResolver dr,SparseArray<ListenerImplContext> mListenerMap,
                                        EventParseCaretaker caretaker) {
        if(info instanceof ImagePropertyBindInfo){
           checkAndGetImageApplier().apply((ImageView) vp.getView(id),
                   dr, (ImagePropertyBindInfo) info);
           // applyImageProperty(vp.getView(id), dr, (ImagePropertyBindInfo) info);
        }else {
            caretaker.beginParse(id, layoutId, info.propertyName, mListenerMap);
            final Object val = info.realExpr.evaluate(dr);
            caretaker.endParse();
            apply(vp, id, layoutId, info.propertyName, val, mListenerMap);
        }
    }

    /**
     * apply the data by target view, often used for adapter
     * @param info  may be {@link com.heaven7.databinding.core.DataBindParser.ImagePropertyBindInfo}
     */
    private static void applyDataReally(View v, int layoutId,PropertyBindInfo info, ViewHelper vp,
                                        IDataResolver dr,SparseArray<ListenerImplContext> mListenerMap,
                                        EventParseCaretaker caretaker) {
        if(info instanceof ImagePropertyBindInfo){
            checkAndGetImageApplier().apply((ImageView) v, dr, (ImagePropertyBindInfo) info);
            //applyImageProperty(v, dr, (ImagePropertyBindInfo) info);
        }else {
            final int id = v.hashCode();
            caretaker.beginParse(id, layoutId, info.propertyName, mListenerMap);
            final Object val = info.realExpr.evaluate(dr);
            caretaker.endParse();
            apply(null, v, id, layoutId, info.propertyName, val, mListenerMap);
        }
    }
    @Override
    public void onEvaluateCallback(Object holder, Method method, Object... params) {
        //if(!v.hasOnClickListeners() //api 15
        mEventCareTaker.update(method,holder,params);
    }

    public <T extends ISelectable> AdapterManager<T> bindAdapter(int id, List<T> data, Object...extras) {
        if(mAdapterParser == null){
            throw new DataBindException("have you declared bindAdapter element ? ");
        }
        if( data == null || data.size() ==0){
            throw new DataBindException("there is no data to bind ");
        }
        final Array<ItemBindInfo> itemBindInfos = mAdapterParser.mItemBinds.get(id);
        if( itemBindInfos.size == 0){
            throw new DataBindException("no item to bind ,have you declared <item> element in bindAdapter element ? ");
        }
       // prepare : check if the adapter previous exist or not, if exist remove previous.
        Object preAdapter = mAdapterParser.mAdapterMap.get(id);
        if(preAdapter!=null) {
            mDataResolver.removeAdapterManager(preAdapter.hashCode());
        }

        // 1, check all refer variable and mapping
        final AdapterInfo adapterInfo = getAdapterInfo(id, data, extras);
        mapAdapterExtraData(id, extras, adapterInfo);

        final View adapterView = mViewHelper.getView(id);

        // 2, apply in adapter
        Object adapter;
        if(adapterView instanceof AdapterView ){
            QuickAdapterImpl<T> impl = new QuickAdapterImpl<>(data,itemBindInfos,adapterInfo);
            ((AdapterView) adapterView).setAdapter(impl);
            adapter = impl;
        }else if ( adapterView instanceof RecyclerView){
            QuickRecycleAdapterImpl<T> impl = new QuickRecycleAdapterImpl<>(data,itemBindInfos,adapterInfo);
            ((RecyclerView) adapterView).setAdapter(impl);
            adapter = impl;
        }else{
            throw new UnsupportedOperationException("unsupport adapter view , classname = "+
                    adapterView.getClass().getName());
        }
        mAdapterParser.mAdapterMap.put(id, adapter);
        // 3, clear datas , can't clear in adapter
         /*
        here have a bug , if we clear memory cache,by calling dataResolver.clearObjects().
        must cause bug,can't find  the listener of mapping . eg: itemHandler.
        so or mDataResolver.endBind() -> param wrong
        */
        // mDataResolver.endBind();
       // mDataResolver.clearObjects();

        return ((AdapterManager.IAdapterManagerCallback<T>)adapter).getAdapterManager();
    }

    /**
     * check the refer and mapping the all find the refer VariableInfos in bindAdapter element.
     * @param id the id of adapter view
     * @param extras  the extra data
     * @param info  the AdapterInfo info,can be null ,if not need check refer.
     * @param <T>  the bean
     */
    private <T extends ISelectable> void mapAdapterExtraData(int id, Object[] extras,AdapterInfo info) {
        if(info == null) {
            info = mAdapterParser.mOtherInfoMap.get(id);
        }
        if( info.getOtherRefers()!=null && info.getOtherRefers().length > 0) {
            getReferVariableInfos(info.getOtherRefers(),extras, mTmpVariables);

            final Array<VariableInfo> variables = this.mTmpVariables;
            if(variables.size >0) {
                final IDataResolver mDataResolver = this.mDataResolver;
                VariableInfo info1;
                for (int i = 0, size = variables.size; i < size; i++) {
                    info1 = variables.get(i);
               /* if(sDebug){
                    Logger.i(TAG, "checkReferAndMapping","mDataResolver.putObject ---- varName  = " +
                            info1.variableName);
                }*/
                    mDataResolver.putLongStandingData(info1.variableName, info1.data);
                }
                variables.clear();
            }
        }
    }

    /***
     *  find the adapter info, and check refer ,
     * @param id  the adapter view id
     * @param data the data
     * @param extras the extra data ,often is bind to the listener
     * @return AdapterInfo
     * @throws DataBindException if find AdapterInfo ==null or main refer is wrong
     */
    private <T extends ISelectable> AdapterInfo getAdapterInfo(int id, List<T> data,Object[] extras) throws DataBindException{
        AdapterInfo info = mAdapterParser.mOtherInfoMap.get(id);
        if( info == null){
            throw new DataBindException("have to declared the right <bindAdapter> element with the adapter view id = "+id);
        }
        final String mainRefer = mVariableBeanMap.get(data.get(0).getClass().getName());
        if (!info.mainRefer.equals(mainRefer)) {
            throw new DataBindException("the referVariable is mismatch, referVariable in <bindAdapter> is "
                    + info.mainRefer + " ,but declared in <variable> is " + mainRefer);
        }
        if(info.getOtherRefers()!=null && info.getOtherRefers().length > 0) {
            if (extras == null || extras.length == 0)
                throw new DataBindException("the refers is mismatch with the extra data, because extra data is" +
                        " empty . but refers = " + Arrays.toString(info.getOtherRefers()));
        }
        return info;
    }

    /**
     * @param mMainRefer the mainrefer variables like 'user1,user2' and etc.
     * @param hashCode the hash code of adapter
     */
    private void onBindAdapterData(int position, ViewHelper helper, Object item,
                                   ItemBindInfo bindInfo,String mMainRefer,int hashCode) {
        final SparseArray<ListenerImplContext> mListenerMap = DataBindParser.this.mListenerMap;
        final IDataResolver mDataResolver = DataBindParser.this.mDataResolver;
        final EventParseCaretaker mEventCareTaker = DataBindParser.this.mEventCareTaker;

        //put object
        mDataResolver.putObject(mMainRefer, item);
        mDataResolver.beginBindItem(position,item);

       // final int hashCode = this.hashCode();
        int size = bindInfo.itemEvents !=null ? bindInfo.itemEvents.size : 0;
        PropertyBindInfo info;

        if(size > 0) {
            final View rootView = helper.getRootView();
            rootView.setTag(R.id.key_adapter_hash,hashCode);
            mDataResolver.setCurrentBindingView(rootView);
            for (int i = 0; i < size; i++) {
                info = bindInfo.itemEvents.get(i);
                applyDataReally(rootView, bindInfo.layoutId, info, helper,
                        mDataResolver, mListenerMap, mEventCareTaker);
            }
        }
        size = bindInfo.itemBinds !=null ? bindInfo.itemBinds.size : 0;
        if(size > 0) {
            View v;
            for (int i = 0; i < size; i++) {
                info = bindInfo.itemBinds.get(i);
                v = helper.getView(info.viewId);
                v.setTag(R.id.key_adapter_hash,hashCode);
                mDataResolver.setCurrentBindingView(v);
                applyDataReally(v, bindInfo.layoutId, info, helper,
                        mDataResolver, mListenerMap, mEventCareTaker);
                //below have a bug , that position and item transfer is wrong
          /*  applyDataReally(info.viewId, bindInfo.layoutId, info, helper,
                    mDataResolver, mListenerMap, mEventCareTaker);*/
            }
        }
    }

    public ViewHelper getViewHelper() {
        return mViewHelper;
    }

    class QuickRecycleAdapterImpl<T extends ISelectable> extends AdapterUtil.QuickRecycleAdapter2<T>{

        final String mMainRefer;

        public QuickRecycleAdapterImpl(List<T> data,Array<ItemBindInfo> infos,AdapterInfo info) {
            super(data, infos, info.selectMode);
            this.mMainRefer = info.mainRefer;
        }

        @Override
        protected void onFinalInit() {
            mDataResolver.putAdapterManager(this.hashCode(), getAdapterManager());
        }

        @Override
        public void beforeNotifyDataChanged() {
            mDataResolver.putAdapterManager(this.hashCode(), getAdapterManager());
        }

        @Override
        protected void bindDataImpl(Context context, int position, ViewHelper helper,
                                    int itemLayoutId, T item, ItemBindInfo bindInfo) {
            onBindAdapterData(position, helper, item, bindInfo,mMainRefer,this.hashCode());
        }
    }

    // if use multi item, T must implement ITag. onClick onLongClick in adapter
    // param like (View v,int position,Object item,SelectHelper helper... etc)
    class QuickAdapterImpl<T extends ISelectable> extends AdapterUtil.QuickAdapter2<T>{

        final String mMainRefer;
       // final int mAdapterViewId;

        public QuickAdapterImpl(List<T> data,Array<ItemBindInfo> infos,AdapterInfo info) {
            super(data, infos, info.selectMode);
            this.mMainRefer = info.mainRefer;
        }

        @Override
        protected void onFinalInit() {
            mDataResolver.putAdapterManager(this.hashCode(), getAdapterManager());
        }

        @Override
        protected void beforeNotifyDataChanged() {
            mDataResolver.putAdapterManager(this.hashCode(), getAdapterManager());
        }

        @Override
        protected void bindDataImpl(Context context, int position, ViewHelper helper,
                                    int itemLayoutId, T item, ItemBindInfo bindInfo) {
            onBindAdapterData(position, helper, item, bindInfo, mMainRefer,this.hashCode());
        }
    }

    /*public*/ static class PropertyBindInfo{

        public String propertyName;
        public String [] referVariables;

        public String expression;
        public IExpression realExpr;

        public int viewId ; //just is valid in  bind element(have referVariable )
    }

    /**
     * the image property of bind info
     */
    public static class ImagePropertyBindInfo extends PropertyBindInfo{

         public IExpression url;
        /** the default image expression */
         public IExpression defaultExpre;
        /** the error image expression, when load occour error */
         public IExpression errorExpre;
        /** {@link com.heaven7.databinding.core.xml.elements.ImagePropertyElement#TYPE_ROUND} and other */
         public int type;        //round ,oval,circle
        /** this indicate the round size of the four corner */
         public IExpression roundSizeExpre;
        /** this indicate the border color expression */
         public IExpression borderColorExpre;
        /** this indicate the border width expression */
         public IExpression borderWidthExpre;

        /** the corner info , can be null */
         public CornerInfo cornerInfo;
    }

    /**
     * the corner info of round image
     */
    public static class CornerInfo{
        public IExpression topLeftExpre ;
        public IExpression topRightExpre ;
        public IExpression bottomLeftExpre ;
        public IExpression bottomRightExpre ;
    }

    static class VariableInfo{
        String variableName;
        Object data;
        public VariableInfo(String variableName, Object data) {
            this.variableName = variableName;
            this.data = data;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VariableInfo that = (VariableInfo) o;
            return Objects.equals(variableName, that.variableName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(variableName);
        }
    }

    /*public*/ static class ItemBindInfo implements IResetable{
        int layoutId ;
        int tag;

        Array<PropertyBindInfo> itemBinds;
        Array<PropertyBindInfo> itemEvents;

        @Override
        public void reset() {
            releasePropertyBindInfos(itemBinds);
            releasePropertyBindInfos(itemEvents);
        }
    }

    /***
     * the refer info of bindAdapter element
     */
    static class AdapterInfo {
        String mainRefer ;
        String [] totalRefer;
        int selectMode ;
        /** exclude the main refer */
        private String [] otherRefers;

        public AdapterInfo(String mainRefer, String[] totalRefer, int selectMode) {
            this.mainRefer = mainRefer;
            this.totalRefer = totalRefer;
            this.selectMode = selectMode;
        }
        /** exclude the main refer */
        public String[] getOtherRefers(){
            if(totalRefer == null || totalRefer.length == 0){
                return null;
            }
            if(otherRefers!=null)
                return  otherRefers;
            List<String> list =  new ArrayList<>(Arrays.asList(totalRefer));
            list.remove(mainRefer);
            return  (otherRefers = list.toArray(new String[list.size()]));
        }
    }
    private class InternalElementParserListener implements DataBindingElement.IElementParseListener{

        @Override
        public void onParseDataElement(DataElement e) {
            if(e.getImportElements()!=null){
                for (ImportElement ie : e.getImportElements()){
                    doWithImportElement(ie);
                }
            }
            if(e.getVariableElements()!=null){
                for (VariableElement ie : e.getVariableElements()){
                    doWithVariableElement(ie);
                }
            }
        }

        @Override
        public void onParseBindElements(List<BindElement> e) {
            if(e!=null){
                for (BindElement ie : e) {
                    doWithBindElement(ie);
                }
            }
        }

        @Override
        public void onParseVariableBindElements(List<BindElement> e) {
            if(e!=null){
                for (BindElement ie : e) {
                    doWithVariableBindElement(ie);
                }
            }
        }

        @Override
        public void onParseBindAdapterElements(List<BindAdapterElement> list) {
            if(list == null || list.size() ==0)
                return;
            if(mAdapterParser == null)
                mAdapterParser = new BindAdapterParser(getContext());
             mAdapterParser.onParseBindAdapterElements(list);
        }
    }

    /**
     * the caretaker of event parser
     */
    static class EventParseCaretaker{

        private Method method;
        private Object holder;
        private Object[] params;

        private SparseArray<ListenerImplContext> mListenerMap;
        private String propertyName;
        /** the view id */
        private int id;
        /** this is used in bind adapter or else 0 */
        private int layoutId;

        void beginParse(int viewId,int layoutId, String propertyName, SparseArray<ListenerImplContext> listenerMap){
            this.id = viewId;
            this.propertyName = propertyName;
            this.mListenerMap = listenerMap;
            this.layoutId = layoutId;
        }

        /**
         * must call after {@link IExpression#evaluate(IDataResolver)}
         * and befor {@link PropertyUtil#apply(ViewHelper, int, int,String, Object, SparseArray)}
         */
        void endParse(){
            if(isEventProperty(propertyName)) {
                final int key = getEventKey(id, layoutId, propertyName);
                ListenerImplContext l = mListenerMap.get(key);
                if (l == null) {
                    l = createEventListener(propertyName);
                    mListenerMap.put(key, l);
                }
                l.set(method,holder,params);
            }
            reset();
        }

        private void reset() {
            mListenerMap = null;
            propertyName = null;
            update(null,null,null);
        }

        public void update(Method method, Object holder, Object[] params){
            this.method = method;
            this.holder = holder;
            this.params = params;
        }
    }


}
