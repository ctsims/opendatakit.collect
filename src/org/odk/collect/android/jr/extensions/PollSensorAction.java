/**
 * XForms Action extension to periodically poll a sensor and optionally save its value.
 * @author jschweers
 */
package org.odk.collect.android.jr.extensions;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.javarosa.core.model.Action;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.Recalculate;
import org.javarosa.core.model.data.AnswerDataFactory;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.odk.collect.android.utilities.GeoUtils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class PollSensorAction extends Action implements LocationListener {
	private static String name = "pollsensor";
	private TreeReference target;
	private TreeReference audit;
	private Context context;
	
	private LocationManager mLocationManager;
	private FormDef mModel;
	private TreeReference mContextRef;

	private class ProvidersChangedHandler extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Set<String> providers = GeoUtils.evaluateProviders(mLocationManager);
			requestLocationUpdates(providers);
		}
	}
	
	private class StopPollingTask extends TimerTask {
		@Override
		public void run() {
			mLocationManager.removeUpdates(PollSensorAction.this);
		}
	}
	
	public PollSensorAction(Context c) {
		super(name);
		this.context = c;
	}
	
	public PollSensorAction(Context c, TreeReference target, TreeReference audit) {
		super(name);

		if ((target == null) != (audit == null)) {
			throw new IllegalArgumentException("PollSensorAction requires either both target and audit or neither");
		}
		
		this.target = target;
		this.audit = audit;
		this.context = c;
	}
	
	/**
	 * Deal with a pollsensor action: start getting a GPS fix, and prepare to cancel after maximum amount of time.
	 * @param model The FormDef that triggered the action
	 * @param contextRef
	 */
	public void processAction(FormDef model, TreeReference contextRef) {
		mModel = model;
		mContextRef = contextRef;
		
		// LocationManager needs to be dealt with in the main UI thread, so wrap GPS-checking logic in a Handler
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			public void run() {
				// Start requesting GPS updates
				mLocationManager = (LocationManager) PollSensorAction.this.context.getSystemService(Context.LOCATION_SERVICE);
				Set<String> providers = GeoUtils.evaluateProviders(mLocationManager);
				if (providers.isEmpty()) {
					PollSensorAction.this.context.registerReceiver(
						new ProvidersChangedHandler(), 
						new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
					);
					DialogInterface.OnClickListener onChangeListener = new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int i) {
							if (i == DialogInterface.BUTTON_POSITIVE) {
						 		Intent intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
								PollSensorAction.this.context.startActivity(intent);
							}
						}
					};
					GeoUtils.showNoGpsDialog(PollSensorAction.this.context, onChangeListener);
				}
				requestLocationUpdates(providers);
			}
		});
	}
	
	/**
	 * Start polling for location, based on whatever providers are given, and set up a timeout after MAXIMUM_WAIT is exceeded.
	 * @param providers Set of String objects that may contain LocationManager.GPS_PROVDER and/or LocationManager.NETWORK_PROVIDER
	 */
	private void requestLocationUpdates(Set<String> providers) {
		if (providers.isEmpty()) {
			mLocationManager.removeUpdates(PollSensorAction.this);
			return;
		}
		
		for (String provider : providers) {
			mLocationManager.requestLocationUpdates(provider, 0, 0, PollSensorAction.this);
		}

		// Cancel polling after maximum time is exceeded
		Timer timeout = new Timer();
		timeout.schedule(new StopPollingTask(), GeoUtils.MAXIMUM_WAIT);
	}
	
	public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
		target = (TreeReference)ExtUtil.read(in, TreeReference.class, pf);
		audit = (TreeReference)ExtUtil.read(in, TreeReference.class, pf);
	}

	public void writeExternal(DataOutputStream out) throws IOException {
		if (target != null) {
			ExtUtil.write(out, target);
			ExtUtil.write(out, audit);
		}
		else {
			super.writeExternal(out);
		}
	}
	
	private void setNodeValue(TreeReference ref, Object value) {
		TreeReference qualifiedReference = mContextRef == null ? ref : ref.contextualize(mContextRef);
		EvaluationContext context = new EvaluationContext(mModel.getEvaluationContext(), qualifiedReference);
		AbstractTreeElement node = context.resolveReference(qualifiedReference);
		if(node == null) { throw new NullPointerException("Target of TreeReference " + qualifiedReference.toString(true) +" could not be resolved!"); }
		int dataType = node.getDataType();
		IAnswerData val = Recalculate.wrapData(value, dataType);
		mModel.setValue(val == null ? null: AnswerDataFactory.templateByDataType(dataType).cast(val.uncast()), qualifiedReference);
	}

	/**
	 * If this action has target and audit nodes, update their values with the given location and the current time.
	 * @param location
	 */
	@Override
	public void onLocationChanged(Location location) {
		if (location != null) {
			if (target != null) {
				setNodeValue(target, GeoUtils.locationToString(location));
				setNodeValue(audit, new Date());
			}
			
			if (location.getAccuracy() <= GeoUtils.ACCEPTABLE_ACCURACY) {
				mLocationManager.removeUpdates(this);
			}
		}
	}

	@Override
	public void onProviderDisabled(String provider) { }

	@Override
	public void onProviderEnabled(String provider) { }

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) { }
}
